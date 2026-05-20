import { useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useAppStore } from '../store/useAppStore';
import { invoke } from '@tauri-apps/api/core';
import { convertFileSrc } from '@tauri-apps/api/core';
import { X, Image as ImageIcon, Clock } from 'lucide-react';
import { Capture } from '../types';

export function HistorySidebar() {
  const { isHistoryOpen, toggleHistory, captures, setCaptures, selectCapture, selectedCapture } = useAppStore();

  useEffect(() => {
    if (isHistoryOpen) {
      loadCaptures();
    }
  }, [isHistoryOpen]);

  const loadCaptures = async () => {
    try {
      const caps = await invoke<Capture[]>('get_captures');
      setCaptures(caps);
    } catch (error) {
      console.error('Failed to load captures:', error);
    }
  };

  const handleSelect = (cap: Capture) => {
    selectCapture(cap);
    toggleHistory(); // Auto-close on mobile/small screens or keep open? Let's close for spotlight feel.
  };

  return (
    <AnimatePresence>
      {isHistoryOpen && (
        <motion.div
          initial={{ x: 300, opacity: 0 }}
          animate={{ x: 0, opacity: 1 }}
          exit={{ x: 300, opacity: 0 }}
          transition={{ type: "spring", stiffness: 300, damping: 30 }}
          className="absolute right-0 top-0 bottom-0 w-72 bg-black/40 backdrop-blur-2xl border-l border-white/10 flex flex-col z-40"
        >
          <div className="p-4 border-b border-white/10 flex items-center justify-between bg-white/5" data-tauri-drag-region>
            <div className="flex items-center gap-2 text-white/90 font-medium">
              <Clock size={16} className="text-[var(--accent)]" />
              <span>Capture History</span>
            </div>
            <button
              onClick={toggleHistory}
              className="p-1.5 rounded-md hover:bg-white/10 text-white/50 hover:text-white transition-colors"
            >
              <X size={16} />
            </button>
          </div>

          <div className="flex-1 overflow-y-auto p-3 space-y-3 no-scrollbar">
            {captures.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-white/40 gap-3">
                <ImageIcon size={32} className="opacity-50" />
                <p className="text-sm text-center px-4">No captures found. Press Alt+S to take a screenshot.</p>
              </div>
            ) : (
              captures.map((cap) => {
                const date = new Date(cap.timestamp);
                const timeString = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
                const dateString = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
                const isSelected = selectedCapture?.path === cap.path;

                return (
                  <motion.button
                    key={cap.path}
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                    onClick={() => handleSelect(cap)}
                    className={`w-full text-left rounded-xl overflow-hidden border transition-all ${isSelected
                        ? 'border-[var(--accent)] shadow-[0_0_15px_rgba(124,92,252,0.3)] ring-1 ring-[var(--accent)]'
                        : 'border-white/10 hover:border-white/30 bg-white/5'
                      }`}
                  >
                    <div className="h-28 overflow-hidden bg-black/50">
                      <img
                        src={convertFileSrc(cap.path)}
                        alt={cap.filename}
                        className="w-full h-full object-cover opacity-90 hover:opacity-100 transition-opacity"
                        loading="lazy"
                      />
                    </div>
                    <div className="px-3 py-2 bg-black/60 backdrop-blur-md flex justify-between items-center text-xs text-white/70">
                      <span className="font-medium truncate max-w-[120px]">{cap.filename}</span>
                      <span className="opacity-70">{dateString}, {timeString}</span>
                    </div>
                  </motion.button>
                );
              })
            )}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
