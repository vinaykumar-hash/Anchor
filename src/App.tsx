import { useEffect, useState } from 'react';
import { ChatInterface } from './components/ChatInterface';
import { ToastNotification } from './components/ToastNotification';
import { CaptureNoteModal } from './components/CaptureNoteModal';
import { listen } from '@tauri-apps/api/event';
import { invoke } from '@tauri-apps/api/core';
import { useAppStore } from './store/useAppStore';

function App() {
  const { showToast, selectCapture } = useAppStore();
  const [pendingCapturePath, setPendingCapturePath] = useState<string | null>(null);

  useEffect(() => {
    // Hide the default context menu in production
    if (import.meta.env.PROD) {
      document.addEventListener('contextmenu', e => e.preventDefault());
    }

    // Listen for global shortcut events from Rust
    const unlistenCapture = listen('shortcut-capture', async () => {
      // The Rust side handles the capture and saves it. 
      // We fetch the latest capture and show the note modal.
      try {
        const captures = await invoke<any[]>('get_captures');
        if (captures && captures.length > 0) {
          const latest = captures[0];
          selectCapture(latest);
          // Show the note modal instead of immediately processing
          setPendingCapturePath(latest.path);
        }
      } catch (e) {
        console.error("Failed to process new capture", e);
      }
    });

    const unlistenToggle = listen('shortcut-toggle', () => {
      // The window visibility is handled by Rust.
    });

    return () => {
      unlistenCapture.then(f => f());
      unlistenToggle.then(f => f());
    };
  }, []);

  const handleNoteSubmit = async (note: string) => {
    if (!pendingCapturePath) return;
    const path = pendingCapturePath;
    setPendingCapturePath(null);
    
    showToast(note ? 'Screenshot saved with your note!' : 'Screenshot saved to memory!');
    
    // Fire-and-forget: send to backend for AI processing with the user note
    try {
      await invoke('process_capture_with_note', { 
        path, 
        userNote: note || null 
      });
    } catch (e) {
      console.error('Failed to process capture:', e);
    }
  };

  const handleNoteSkip = async () => {
    if (!pendingCapturePath) return;
    const path = pendingCapturePath;
    setPendingCapturePath(null);
    
    showToast('Screenshot saved to memory!');
    
    // Process without a note
    try {
      await invoke('process_capture_with_note', { 
        path, 
        userNote: null 
      });
    } catch (e) {
      console.error('Failed to process capture:', e);
    }
  };

  return (
    <div className="w-screen h-screen overflow-hidden bg-[var(--app-bg)] select-none">
      <ChatInterface />
      <ToastNotification />
      <CaptureNoteModal
        capturePath={pendingCapturePath}
        onSubmit={handleNoteSubmit}
        onSkip={handleNoteSkip}
      />
    </div>
  );
}

export default App;
