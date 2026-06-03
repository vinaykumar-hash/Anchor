import { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { convertFileSrc } from '@tauri-apps/api/core';
import { Camera, X, Send, Sparkles } from 'lucide-react';

interface CaptureNoteModalProps {
  capturePath: string | null;
  onSubmit: (note: string) => void;
  onSkip: () => void;
}

export function CaptureNoteModal({ capturePath, onSubmit, onSkip }: CaptureNoteModalProps) {
  const [note, setNote] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (capturePath) {
      setNote('');
      // Small delay to let the modal animate in before focusing
      setTimeout(() => inputRef.current?.focus(), 300);
    }
  }, [capturePath]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(note.trim());
    setNote('');
  };

  const handleSkip = () => {
    setNote('');
    onSkip();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      handleSkip();
    }
  };

  return (
    <AnimatePresence>
      {capturePath && (
        <>
          {/* Notification — bottom-right corner */}
          <motion.div
            initial={{ opacity: 0, x: 60, y: 20 }}
            animate={{ opacity: 1, x: 0, y: 0 }}
            exit={{ opacity: 0, x: 60, y: 20 }}
            transition={{ type: 'spring', stiffness: 400, damping: 30 }}
            className="fixed bottom-6 right-6 z-[101] w-[360px] rounded-2xl p-4 shadow-2xl"
            style={{
              background: 'rgba(28, 28, 30, 0.7)',
              backdropFilter: 'blur(24px) saturate(180%)',
              WebkitBackdropFilter: 'blur(24px) saturate(180%)',
              border: '1px solid rgba(255, 255, 255, 0.12)',
              boxShadow: '0 8px 32px rgba(0, 0, 0, 0.5), 0 0 60px rgba(111, 209, 215, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.06)',
            }}
            onKeyDown={handleKeyDown}
          >
            {/* Header */}
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2.5">
                <div
                  className="p-1.5 rounded-lg"
                  style={{
                    background: 'rgba(111, 209, 215, 0.12)',
                    border: '1px solid rgba(111, 209, 215, 0.2)',
                  }}
                >
                  <Camera size={14} className="text-[var(--aqua)]" />
                </div>
                <div>
                  <h3 className="text-xs font-semibold text-white leading-tight">Screenshot Captured</h3>
                  <p className="text-[10px] text-white/40 font-medium">Add an optional note</p>
                </div>
              </div>
              <button
                onClick={handleSkip}
                className="p-1.5 rounded-lg hover:bg-white/10 text-white/30 hover:text-white transition-colors"
              >
                <X size={14} />
              </button>
            </div>

            {/* Screenshot Thumbnail */}
            <div
              className="rounded-xl overflow-hidden mb-3"
              style={{
                border: '1px solid rgba(255, 255, 255, 0.08)',
                maxHeight: '100px',
              }}
            >
              <img
                src={convertFileSrc(capturePath)}
                alt="Captured screenshot"
                className="w-full h-full object-cover"
                style={{ maxHeight: '100px' }}
              />
            </div>

            {/* Input Form */}
            <form onSubmit={handleSubmit} className="flex items-center gap-2">
              <div className="flex-1 relative">
                <input
                  ref={inputRef}
                  type="text"
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  placeholder="e.g. Buy this next month..."
                  className="w-full rounded-xl px-3 py-2.5 text-xs text-white placeholder-white/30 focus:outline-none transition-all"
                  style={{
                    background: 'rgba(255, 255, 255, 0.06)',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                  }}
                  onFocus={(e) => {
                    (e.target as HTMLInputElement).style.borderColor = 'rgba(111, 209, 215, 0.3)';
                  }}
                  onBlur={(e) => {
                    (e.target as HTMLInputElement).style.borderColor = 'rgba(255, 255, 255, 0.1)';
                  }}
                />
              </div>
              <button
                type="submit"
                className="shrink-0 w-9 h-9 rounded-lg flex items-center justify-center transition-all hover:scale-105 active:scale-95"
                style={{
                  background: 'rgba(111, 209, 215, 0.15)',
                  border: '1px solid rgba(111, 209, 215, 0.25)',
                }}
                title={note.trim() ? 'Save with note' : 'Save without note'}
              >
                <Send size={14} className="text-[var(--aqua)]" />
              </button>
            </form>

            {/* Skip hint */}
            <p className="text-center text-[10px] text-white/25 mt-2 font-medium">
              <kbd className="px-1 py-0.5 rounded bg-white/10 text-white/40 font-mono text-[9px]">Enter</kbd> save · <kbd className="px-1 py-0.5 rounded bg-white/10 text-white/40 font-mono text-[9px]">Esc</kbd> skip
            </p>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
