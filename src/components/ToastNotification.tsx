import { motion, AnimatePresence } from 'framer-motion';
import { useAppStore } from '../store/useAppStore';
import { CheckCircle2 } from 'lucide-react';

export function ToastNotification() {
  const toastMessage = useAppStore((state) => state.toastMessage);

  return (
    <AnimatePresence>
      {toastMessage && (
        <motion.div
          initial={{ opacity: 0, y: -20, scale: 0.9 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: -20, scale: 0.9 }}
          transition={{ type: "spring", stiffness: 400, damping: 25 }}
          className="fixed top-6 right-6 z-50 glass-panel rounded-full px-5 py-3 flex items-center gap-3 text-white/90 shadow-2xl"
          data-tauri-drag-region
        >
          <div className="bg-green-500/20 p-1.5 rounded-full border border-green-500/30 text-green-400">
            <CheckCircle2 size={18} />
          </div>
          <span className="text-sm font-medium tracking-wide">{toastMessage}</span>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
