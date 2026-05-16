import { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { invoke } from '@tauri-apps/api/core';
import { X, Trash2, Loader2, Database } from 'lucide-react';

interface MemoryVaultPanelProps {
  onClose: () => void;
}

interface MemoryEntry {
  timestamp: string;
  text: string;
  image_path?: string;
}

export function MemoryVaultPanel({ onClose }: MemoryVaultPanelProps) {
  const [memories, setMemories] = useState<MemoryEntry[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    fetchMemories();
  }, []);

  const fetchMemories = async () => {
    setIsLoading(true);
    setError(null);
    try {
      const resultStr = await invoke<string>('get_all_memories');
      const result = JSON.parse(resultStr);
      if (result.status === 'success') {
        setMemories(result.memories || []);
      } else {
        setError(result.message || 'Failed to fetch memories');
      }
    } catch (e: any) {
      setError(e.message || 'Error communicating with backend');
    }
    setIsLoading(false);
  };

  const handleDelete = async (timestamp: string) => {
    if (!confirm('Are you sure you want to delete this memory? It cannot be undone.')) return;
    
    setDeletingId(timestamp);
    try {
      const resultStr = await invoke<string>('delete_memory', { timestamp });
      const result = JSON.parse(resultStr);
      if (result.status === 'success') {
        setMemories(prev => prev.filter(m => m.timestamp !== timestamp));
      } else {
        alert(`Failed to delete: ${result.message}`);
      }
    } catch (e: any) {
      alert(`Error deleting memory: ${e.message || String(e)}`);
    }
    setDeletingId(null);
  };

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      className="absolute inset-0 z-50 flex items-center justify-center bg-[#f7f5ef]/70 backdrop-blur-sm p-4"
      onClick={onClose}
    >
      <motion.div
        initial={{ scale: 0.95, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        exit={{ scale: 0.95, opacity: 0 }}
        className="bg-white/95 backdrop-blur-md w-full max-w-4xl h-[80vh] rounded-3xl shadow-2xl border border-black/[0.06] flex flex-col overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-black/[0.06] bg-[#f4f1ea]">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-[var(--accent)]/15 rounded-xl">
              <Database className="w-5 h-5 text-[var(--accent)]" />
            </div>
            <div>
              <h2 className="text-xl font-semibold text-[var(--ink)]">Memory Vault</h2>
              <p className="text-sm text-[var(--muted)]">View and manage your captured context</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-[var(--muted)] hover:text-[var(--ink)] hover:bg-black/[0.04] rounded-xl transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6 scrollbar-thin scrollbar-thumb-white/10 scrollbar-track-transparent">
          {isLoading ? (
            <div className="h-full flex flex-col items-center justify-center text-[var(--muted)]">
              <Loader2 className="w-8 h-8 animate-spin mb-4 text-[var(--accent)]" />
              <p>Loading your memories...</p>
            </div>
          ) : error ? (
            <div className="h-full flex items-center justify-center text-red-400 text-center">
              <p>{error}</p>
            </div>
          ) : memories.length === 0 ? (
            <div className="h-full flex items-center justify-center text-[var(--muted)]">
              <p>Your vault is empty. Try capturing some screen context first!</p>
            </div>
          ) : (
            <div className="space-y-4">
              <div className="text-sm text-[var(--muted)] mb-4 px-2">
                Total Memories: {memories.length}
              </div>
              
              {memories.map((memory) => {
                const date = new Date(memory.timestamp);
                const isDeleting = deletingId === memory.timestamp;
                
                return (
                  <div 
                    key={memory.timestamp}
                    className="bg-[#fdfcf8] rounded-2xl p-4 border border-black/[0.05] hover:border-black/[0.1] transition-colors relative group"
                  >
                    <div className="flex justify-between items-start mb-2 gap-4">
                      <div className="text-xs font-mono text-[var(--accent)] bg-[var(--accent)]/10 px-2 py-1 rounded-md">
                        {date.toLocaleString()}
                      </div>
                      <button
                        onClick={() => handleDelete(memory.timestamp)}
                        disabled={isDeleting}
                        className={`p-2 rounded-xl text-red-400 opacity-0 group-hover:opacity-100 transition-all
                          ${isDeleting ? 'opacity-100 bg-red-500/20' : 'hover:bg-red-500/20 hover:text-red-300'}
                        `}
                        title="Delete Memory"
                      >
                        {isDeleting ? (
                          <Loader2 className="w-4 h-4 animate-spin" />
                        ) : (
                          <Trash2 className="w-4 h-4" />
                        )}
                      </button>
                    </div>
                    
                    <div className="text-sm text-[var(--ink)] whitespace-pre-wrap font-sans leading-relaxed max-h-48 overflow-y-auto scrollbar-thin scrollbar-thumb-black/10 pr-2">
                      {memory.text}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </motion.div>
    </motion.div>
  );
}
