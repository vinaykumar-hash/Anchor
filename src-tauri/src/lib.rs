use std::fs;
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::Mutex;
use std::io::{BufRead, BufReader, Write};
use std::process::{Command, Stdio, ChildStdin, ChildStdout};
use chrono::Local;
use tauri::{AppHandle, Manager, Emitter, State};
use tauri::tray::{TrayIconBuilder, MouseButton, MouseButtonState, TrayIconEvent};
use tauri::menu::{Menu, MenuItem};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState};
use tauri_plugin_shell::ShellExt;
use tauri_plugin_notification::NotificationExt;
use serde::{Serialize, Deserialize};
use serde_json::Value;

#[derive(Serialize, Deserialize)]
pub struct CaptureInfo {
    pub path: String,
    pub filename: String,
    pub timestamp: i64,
}

struct AiSidecar {
    io: Mutex<Option<(ChildStdin, BufReader<ChildStdout>)>>,
}

// Ensure the captures directory exists
fn get_captures_dir(app: &AppHandle) -> PathBuf {
    let app_local_data = app.path().app_local_data_dir().unwrap();
    let captures_dir = app_local_data.join("captures");
    if !captures_dir.exists() {
        fs::create_dir_all(&captures_dir).unwrap();
    }
    captures_dir
}

fn init_sidecar(app: &AppHandle) -> Result<(ChildStdin, BufReader<ChildStdout>), String> {
    #[cfg(debug_assertions)]
    {
        let mut child = Command::new("python")
            .arg("binaries/ai_processor.py")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|e| format!("Failed to spawn python: {}", e))?;
            
        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        Ok((stdin, BufReader::new(stdout)))
    }
    
    #[cfg(not(debug_assertions))]
    {
        // For production, use the sidecar API (Note: currently requires standard std::process for pipes)
        // tauri_plugin_shell sidecar doesn't easily expose raw synchronous Stdin/Stdout pipes. 
        // We'll use std::process::Command with the bundled path.
        let sidecar_path = app.path().resolve("binaries/ai_processor", tauri::path::BaseDirectory::Resource)
            .map_err(|e| e.to_string())?;
            
        let mut child = Command::new(sidecar_path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .map_err(|e| format!("Failed to spawn sidecar: {}", e))?;
            
        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        Ok((stdin, BufReader::new(stdout)))
    }
}

fn communicate_with_sidecar(app: &AppHandle, payload: Value) -> Result<String, String> {
    let state: State<AiSidecar> = app.state();
    let mut io_guard = state.io.lock().unwrap();
    
    if io_guard.is_none() {
        *io_guard = Some(init_sidecar(app)?);
    }
    
    if let Some((stdin, stdout)) = io_guard.as_mut() {
        let mut json_str = payload.to_string();
        json_str.push('\n');
        
        stdin.write_all(json_str.as_bytes()).map_err(|e| format!("Write error: {}", e))?;
        stdin.flush().map_err(|e| format!("Flush error: {}", e))?;
        
        let mut response = String::new();
        stdout.read_line(&mut response).map_err(|e| format!("Read error: {}", e))?;
        
        Ok(response)
    } else {
        Err("Failed to initialize sidecar IO".into())
    }
}

#[tauri::command]
fn capture_screen(app: AppHandle) -> Result<CaptureInfo, String> {
    let monitors = xcap::Monitor::all().map_err(|e| e.to_string())?;
    
    // Attempt to find primary monitor, otherwise fallback to the first monitor
    let monitor = monitors.into_iter().find(|m| m.is_primary().unwrap_or(false)).or_else(|| {
        xcap::Monitor::all().ok().and_then(|mut ms| if ms.is_empty() { None } else { Some(ms.remove(0)) })
    }).ok_or("No monitors found")?;

    let image = monitor.capture_image().map_err(|e| e.to_string())?;
    
    let now = Local::now();
    let timestamp = now.timestamp_millis();
    let filename = format!("cap_{}.png", now.format("%Y%m%d_%H%M%S"));
    
    let captures_dir = get_captures_dir(&app);
    let filepath = captures_dir.join(&filename);
    
    image.save(&filepath).map_err(|e| e.to_string())?;
    
    // Auto-process the screenshot in the background
    let app_clone = app.clone();
    let filepath_clone = filepath.clone();
    std::thread::spawn(move || {
        let payload = serde_json::json!({
            "action": "process",
            "path": filepath_clone.to_string_lossy().to_string()
        });
        match communicate_with_sidecar(&app_clone, payload) {
            Ok(resp) => {
                eprintln!("[ContextMemory] Auto-process OK: {}", resp.trim());
            }
            Err(e) => {
                eprintln!("[ContextMemory] Auto-process FAILED: {}", e);
            }
        }
    });
    
    // Send a toast notification
    let _ = app.notification()
        .builder()
        .title("ContextMemory")
        .body("Screenshot captured and processing!")
        .show();

    Ok(CaptureInfo {
        path: filepath.to_string_lossy().to_string(),
        filename,
        timestamp,
    })
}

#[tauri::command]
fn get_captures(app: AppHandle) -> Result<Vec<CaptureInfo>, String> {
    let captures_dir = get_captures_dir(&app);
    let mut captures = Vec::new();
    
    if let Ok(entries) = fs::read_dir(captures_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|s| s.to_str()) == Some("png") {
                if let Ok(metadata) = entry.metadata() {
                    let timestamp = metadata.created().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_millis() as i64).unwrap_or(0);
                    captures.push(CaptureInfo {
                        path: path.to_string_lossy().to_string(),
                        filename: entry.file_name().to_string_lossy().to_string(),
                        timestamp,
                    });
                }
            }
        }
    }
    
    // Sort by newest first
    captures.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
    Ok(captures)
}

#[tauri::command]
fn invoke_ai_processing(app: AppHandle, path: String) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "process",
        "path": path
    });
    communicate_with_sidecar(&app, payload)
}

#[tauri::command]
async fn search_context(query: String, app: tauri::AppHandle) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "search",
        "query": query
    });
    
    communicate_with_sidecar(&app, payload)
}

#[tauri::command]
async fn ask_agent(query: String, app: tauri::AppHandle) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "ask",
        "query": query
    });
    
    let state: State<AiSidecar> = app.state();
    let mut io_guard = state.io.lock().unwrap();
    
    if io_guard.is_none() {
        *io_guard = Some(init_sidecar(&app)?);
    }
    
    if let Some((stdin, stdout)) = io_guard.as_mut() {
        let mut json_str = payload.to_string();
        json_str.push('\n');
        
        stdin.write_all(json_str.as_bytes()).map_err(|e| format!("Write error: {}", e))?;
        stdin.flush().map_err(|e| format!("Flush error: {}", e))?;
        
        loop {
            let mut response = String::new();
            stdout.read_line(&mut response).map_err(|e| format!("Read error: {}", e))?;
            
            let val: Value = serde_json::from_str(&response).map_err(|e| format!("JSON parse error: {}", e))?;
            let action = val.get("action").and_then(|a| a.as_str()).unwrap_or("");
            
            if action == "ask_chunk" {
                if let Some(chunk) = val.get("chunk").and_then(|c| c.as_str()) {
                    let _ = app.emit("ai-chunk", chunk);
                }
            } else if action == "ask" {
                return Ok(response);
            } else {
                return Ok(response);
            }
        }
    } else {
        Err("Failed to initialize sidecar IO".into())
    }
}

#[tauri::command]
async fn set_gpu_mode(useGpu: bool, app: tauri::AppHandle) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "set_gpu_mode",
        "use_gpu": useGpu
    });
    
    communicate_with_sidecar(&app, payload)
}

#[tauri::command]
async fn get_sync_status(app: tauri::AppHandle) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "sync_status"
    });
    
    communicate_with_sidecar(&app, payload)
}

#[tauri::command]
async fn toggle_auto_sync(enabled: bool, app: tauri::AppHandle) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "toggle_auto_sync",
        "enabled": enabled
    });
    
    communicate_with_sidecar(&app, payload)
}

#[tauri::command]
async fn sync_with(ip: String, port: i32, app: tauri::AppHandle) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "sync_with",
        "ip": ip,
        "port": port
    });
    
    communicate_with_sidecar(&app, payload)
}

#[tauri::command]
async fn get_all_memories(app: tauri::AppHandle) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "get_all_memories"
    });
    
    communicate_with_sidecar(&app, payload)
}

#[tauri::command]
async fn delete_memory(timestamp: String, app: tauri::AppHandle) -> Result<String, String> {
    let payload = serde_json::json!({
        "action": "delete_memory",
        "timestamp": timestamp
    });
    
    communicate_with_sidecar(&app, payload)
}

#[tauri::command]
fn toggle_chat_window(app: AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let is_visible = window.is_visible().unwrap_or(false);
        if is_visible {
            let _ = window.hide();
        } else {
            let _ = window.show();
            let _ = window.set_focus();
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .setup(|app| {
            // Setup System Tray
            let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let show_i = MenuItem::with_id(app, "show", "Show Chat", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_i, &quit_i])?;
            
            let tray = TrayIconBuilder::new()
                .menu(&menu)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "quit" => {
                        std::process::exit(0);
                    }
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let is_visible = window.is_visible().unwrap_or(false);
                            if is_visible {
                                let _ = window.hide();
                            } else {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    }
                })
                .build(app)?;

            // Register Global Shortcuts
            let app_handle = app.handle().clone();
            
            // Alt+S for Capture
            let capture_shortcut = Shortcut::from_str("Alt+S").unwrap();
            app.global_shortcut().on_shortcut(capture_shortcut, move |app, shortcut, event| {
                if event.state == ShortcutState::Pressed {
                    let _ = capture_screen(app.clone());
                    // Notify frontend
                    let _ = app.emit("shortcut-capture", ());
                }
            }).unwrap_or_else(|e| eprintln!("Warning: Failed to register capture shortcut: {}", e));
            
            // Alt+Shift+Space for Chat Toggle
            let chat_shortcut = Shortcut::from_str("Alt+Shift+Space").unwrap();
            app.global_shortcut().on_shortcut(chat_shortcut, move |app, shortcut, event| {
                if event.state == ShortcutState::Pressed {
                    toggle_chat_window(app.clone());
                    // Notify frontend
                    let _ = app.emit("shortcut-toggle", ());
                }
            }).unwrap_or_else(|e| eprintln!("Warning: Failed to register chat toggle shortcut: {}", e));

            Ok(())
        })
        .manage(AiSidecar { io: Mutex::new(None) })
        .invoke_handler(tauri::generate_handler![
            capture_screen,
            get_captures,
            invoke_ai_processing,
            search_context,
            ask_agent,
            set_gpu_mode,
            get_sync_status,
            sync_with,
            toggle_auto_sync,
            toggle_chat_window,
            get_all_memories,
            delete_memory
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
