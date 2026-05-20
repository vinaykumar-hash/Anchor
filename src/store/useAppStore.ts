import { create } from 'zustand';
import { Message, Capture } from '../types';

interface SearchResult {
  path: string;
  text: string;
  score: number;
}

interface AppState {
  messages: Message[];
  captures: Capture[];
  selectedCapture: Capture | null;
  searchResults: SearchResult[];
  isHistoryOpen: boolean;
  toastMessage: string | null;

  addMessage: (msg: Message) => void;
  updateMessage: (id: string, content: string) => void;
  setCaptures: (caps: Capture[]) => void;
  selectCapture: (cap: Capture | null) => void;
  setSearchResults: (results: SearchResult[]) => void;
  toggleHistory: () => void;
  showToast: (msg: string) => void;
  clearToast: () => void;

  useGpu: boolean;
  toggleGpu: () => void;
}

export const useAppStore = create<AppState>((set) => ({
  messages: [{
    id: 'welcome',
    role: 'assistant',
    content: 'Hello! I am Anchor — your personal memory assistant. Press Alt+S to capture your screen, and ask me anything about what you\'ve seen.',
    timestamp: Date.now()
  }],
  captures: [],
  selectedCapture: null,
  searchResults: [],
  isHistoryOpen: false,
  toastMessage: null,

  addMessage: (msg) => set((state) => ({ messages: [...state.messages, msg] })),
  updateMessage: (id, content) => set((state) => ({
    messages: state.messages.map(m => m.id === id ? { ...m, content } : m)
  })),
  setCaptures: (caps) => set({ captures: caps }),
  selectCapture: (cap) => set({ selectedCapture: cap }),
  setSearchResults: (results) => set({ searchResults: results }),
  toggleHistory: () => set((state) => ({ isHistoryOpen: !state.isHistoryOpen })),
  showToast: (msg) => {
    set({ toastMessage: msg });
    setTimeout(() => {
      set({ toastMessage: null });
    }, 3000);
  },
  clearToast: () => set({ toastMessage: null }),

  useGpu: localStorage.getItem('useGpu') === 'true',
  toggleGpu: () => set((state) => {
    const newVal = !state.useGpu;
    localStorage.setItem('useGpu', newVal.toString());

    // Attempt to notify backend (fire-and-forget)
    try {
      import('@tauri-apps/api/core').then(({ invoke }) => {
        invoke('set_gpu_mode', { useGpu: newVal }).catch(console.error);
      });
    } catch (e) { }

    return { useGpu: newVal };
  }),
}));
