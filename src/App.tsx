import { useEffect } from 'react';
import { ChatInterface } from './components/ChatInterface';
import { ToastNotification } from './components/ToastNotification';
import { listen } from '@tauri-apps/api/event';
import { invoke } from '@tauri-apps/api/core';
import { useAppStore } from './store/useAppStore';

function App() {
  const { showToast, addMessage, selectCapture } = useAppStore();

  useEffect(() => {
    // Hide the default context menu in production
    if (import.meta.env.PROD) {
      document.addEventListener('contextmenu', e => e.preventDefault());
    }

    // Listen for global shortcut events from Rust
    const unlistenCapture = listen('shortcut-capture', async () => {
      // The Rust side handles the capture and saves it. 
      // We can fetch the latest capture here.
      try {
        const captures = await invoke<any[]>('get_captures');
        if (captures && captures.length > 0) {
          const latest = captures[0];
          showToast('Screenshot saved to context memory');
          
          // Optionally, automatically select the new capture
          selectCapture(latest);
        }
      } catch (e) {
        console.error("Failed to process new capture", e);
      }
    });

    const unlistenToggle = listen('shortcut-toggle', () => {
      // The window visibility is handled by Rust.
      // We could add sound effects or focus input here if needed.
    });

    return () => {
      unlistenCapture.then(f => f());
      unlistenToggle.then(f => f());
    };
  }, []);

  return (
    <div className="w-screen h-screen p-3 overflow-hidden bg-[var(--app-bg)] select-none">
      <ChatInterface />
      <ToastNotification />
    </div>
  );
}

export default App;
