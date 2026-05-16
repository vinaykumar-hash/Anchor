import { useState, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useAppStore } from '../store/useAppStore';
import { MessageBubble } from './MessageBubble';
import { HistorySidebar } from './HistorySidebar';
import { SyncPanel } from './SyncPanel';
import { MemoryVaultPanel } from './MemoryVaultPanel';
import { invoke, convertFileSrc } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { Send, History, Sparkles, X, Settings, Cpu, Zap, Wifi, Menu, Database } from 'lucide-react';

export function ChatInterface() {
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isSyncOpen, setIsSyncOpen] = useState(false);
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [isVaultOpen, setIsVaultOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const { messages, addMessage, updateMessage, selectedCapture, selectCapture, toggleHistory, isHistoryOpen, useGpu, toggleGpu } = useAppStore();
  const currentAssistantMsgId = useRef<string | null>(null);
  const currentAssistantContent = useRef<string>('');

  useEffect(() => {
    // Listen for streaming chunks from AI
    const unlistenChunks = listen<string>('ai-chunk', (event) => {
      if (currentAssistantMsgId.current) {
        currentAssistantContent.current += event.payload;
        updateMessage(currentAssistantMsgId.current, currentAssistantContent.current);
      }
    });

    return () => {
      unlistenChunks.then(f => f());
    };
  }, []);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
    // Sync GPU setting with backend on startup
    invoke('set_gpu_mode', { useGpu });
  }, [messages, isLoading, useGpu]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || isLoading) return;

    const userMsg = input.trim();
    setInput('');

    // Add user message
    addMessage({
      id: Date.now().toString(),
      role: 'user',
      content: userMsg,
      imageUrl: selectedCapture?.path,
      timestamp: Date.now()
    });

    setIsLoading(true);

    try {
      if (userMsg.toLowerCase().startsWith('/search ')) {
        const query = userMsg.substring(8).trim();
        const resultStr = await invoke<string>('search_context', { query });

        try {
          const resultJson = JSON.parse(resultStr);
          if (resultJson.results && resultJson.results.length > 0) {
            addMessage({
              id: (Date.now() + 1).toString(),
              role: 'assistant',
              content: `SEARCH_RESULTS:${JSON.stringify(resultJson.results)}`,
              timestamp: Date.now()
            });
          } else {
            addMessage({
              id: (Date.now() + 1).toString(),
              role: 'assistant',
              content: `No results found for "${query}".`,
              timestamp: Date.now()
            });
          }
        } catch (e) {
          addMessage({
            id: (Date.now() + 1).toString(),
            role: 'assistant',
            content: `Failed to parse search results: ${e}`,
            timestamp: Date.now()
          });
        }
      } else {
        // Normal AI processing
        if (selectedCapture) {
          const resultStr = await invoke<string>('invoke_ai_processing', { path: selectedCapture.path });

          let responseContent = "I analyzed the image, but couldn't generate a description.";
          try {
            const resultJson = JSON.parse(resultStr);
            responseContent = resultJson.description || responseContent;
          } catch (e) {
            responseContent = `Raw response: ${resultStr}`;
          }

          addMessage({
            id: (Date.now() + 1).toString(),
            role: 'assistant',
            content: responseContent,
            timestamp: Date.now()
          });

        } else if (userMsg.toLowerCase().trim() === 'hello' || userMsg.toLowerCase().trim() === 'hi') {
          addMessage({
            id: (Date.now() + 1).toString(),
            role: 'assistant',
            content: "Hello! I'm ContextMemory. Whenever you take screenshots with `Alt+S`, they are automatically added to your database. Ask me anything to search your memory!",
            timestamp: Date.now()
          });
        } else {
          // Default behavior: Ask Agent (RAG Pipeline)
          const assistantId = (Date.now() + 1).toString();
          currentAssistantMsgId.current = assistantId;
          currentAssistantContent.current = '';
          
          addMessage({
            id: assistantId,
            role: 'assistant',
            content: '',
            timestamp: Date.now()
          });

          const resultStr = await invoke<string>('ask_agent', { query: userMsg });

          try {
            const resultJson = JSON.parse(resultStr);
            if (resultJson.answer) {
              updateMessage(assistantId, `RAG_RESULT:${JSON.stringify({ answer: resultJson.answer, sources: resultJson.sources })}`);
            } else {
              updateMessage(assistantId, "I couldn't process your question properly.");
            }
          } catch (e) {
            updateMessage(assistantId, `Failed to search your memory: ${e}`);
          } finally {
            currentAssistantMsgId.current = null;
          }
        }
      }
    } catch (error) {
      console.error('Error invoking backend:', error);
      addMessage({
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: `Sorry, there was an error processing your request: ${error}`,
        timestamp: Date.now()
      });
    } finally {
      setIsLoading(false);
      // Clear the selected capture after asking about it to reset state
      selectCapture(null);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
    // Close window on Escape
    if (e.key === 'Escape') {
      invoke('toggle_chat_window');
    }
  };

  const isEmpty = messages.length === 0;

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.95, y: 20 }}
      animate={{ opacity: 1, scale: 1, y: 0 }}
      exit={{ opacity: 0, scale: 0.95, y: 20 }}
      transition={{ duration: 0.2, ease: "easeOut" }}
      className="w-full h-full rounded-[34px] overflow-hidden flex flex-col relative shadow-[0_30px_80px_rgba(43,38,30,0.16)] border border-black/[0.06] bg-[var(--app-bg)]"
    >
      {/* Top Drag Region */}
      <div data-tauri-drag-region className="absolute top-0 left-0 right-0 h-16 z-40 cursor-move" />

      {/* Floating Menu Button */}
      <div className="absolute top-5 right-5 z-50">
        <button
          onClick={() => setIsMenuOpen(!isMenuOpen)}
          className={`p-3 rounded-full transition-colors shadow-sm ${isMenuOpen ? 'bg-white text-[var(--ink)]' : 'bg-white/80 text-[var(--ink)] hover:bg-white'}`}
        >
          <Menu size={20} />
        </button>

        <AnimatePresence>
          {isMenuOpen && (
            <motion.div
              initial={{ opacity: 0, scale: 0.95, originX: 1, originY: 0 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.95 }}
              className="absolute top-full right-0 mt-2 w-52 bg-white/95 border border-black/[0.06] rounded-2xl shadow-2xl py-2 overflow-hidden backdrop-blur-xl"
            >
              <button onClick={() => { setIsSettingsOpen(true); setIsMenuOpen(false); }} className="w-full px-4 py-2.5 text-left text-sm text-[var(--ink)] hover:bg-black/[0.04] flex items-center gap-3"><Settings size={16} /> Settings</button>
              <button onClick={() => { setIsSyncOpen(true); setIsMenuOpen(false); }} className="w-full px-4 py-2.5 text-left text-sm text-[var(--ink)] hover:bg-black/[0.04] flex items-center gap-3"><Wifi size={16} /> Device Sync</button>
              <button onClick={() => { setIsVaultOpen(true); setIsMenuOpen(false); }} className="w-full px-4 py-2.5 text-left text-sm text-[var(--ink)] hover:bg-black/[0.04] flex items-center gap-3"><Database size={16} /> Memory Vault</button>
              <button onClick={() => { toggleHistory(); setIsMenuOpen(false); }} className="w-full px-4 py-2.5 text-left text-sm text-[var(--ink)] hover:bg-black/[0.04] flex items-center gap-3"><History size={16} /> History</button>
              <div className="h-px bg-black/[0.06] my-1" />
              <button onClick={() => { invoke('toggle_chat_window'); setIsMenuOpen(false); }} className="w-full px-4 py-2.5 text-left text-sm text-[var(--muted)] hover:bg-black/[0.04] flex items-center gap-3"><X size={16} /> Close</button>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* Main Content Area */}
      {isEmpty ? (
        <div className="flex-1 flex flex-col items-center justify-center relative z-10 px-8 pb-24">
          <div className="w-full max-w-[460px] min-h-[420px] rounded-[42px] bg-[var(--surface)] border border-black/[0.04] shadow-[0_20px_80px_rgba(70,58,40,0.12)] flex flex-col items-center justify-start pt-14 px-8 overflow-hidden relative">
            <p className="text-xs uppercase tracking-[0.16em] text-[var(--muted)] font-semibold mb-3">Hello there</p>
            <h1 className="text-[42px] leading-[1.02] font-semibold text-[var(--ink)] text-center tracking-[-0.04em]">
              How can I help<br />you remember?
            </h1>
            <div className="flex flex-wrap justify-center gap-3 mt-7 text-sm text-[var(--ink)]">
              <span className="px-5 py-2.5 rounded-full bg-white shadow-sm">Search memory</span>
              <span className="px-5 py-2.5 rounded-full bg-white shadow-sm">Organize context</span>
              <span className="px-5 py-2.5 rounded-full bg-white shadow-sm">Find activity</span>
            </div>
            <div className="absolute -bottom-12 left-[-12%] right-[-12%] h-[210px] rounded-t-[100%] blur-[1px] opacity-95 bg-[var(--sunset)]" />
            <div className="absolute bottom-0 left-[20%] right-[-20%] h-[155px] rounded-tl-[100%] bg-[rgba(255,255,255,0.62)]" />
          </div>
        </div>
      ) : (
        <div className="flex-1 overflow-hidden relative flex flex-col pt-16 z-10">
          <div ref={scrollRef} className="flex-1 overflow-y-auto px-6 space-y-6 no-scrollbar pb-32">
            {messages.map((msg) => (
              <MessageBubble key={msg.id} message={msg} />
            ))}

            {isLoading && (
              <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="flex gap-3 max-w-[85%]">
                <div className="w-8 h-8 rounded-full bg-white border border-black/[0.05] flex items-center justify-center text-[var(--accent)]">
                  <Sparkles size={14} className="animate-pulse" />
                </div>
                <div className="bg-white border border-black/[0.04] p-3.5 rounded-2xl rounded-tl-sm backdrop-blur-md">
                  <div className="flex gap-1.5">
                    <span className="w-1.5 h-1.5 bg-[var(--accent)]/50 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                    <span className="w-1.5 h-1.5 bg-[#ff8b63]/60 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                    <span className="w-1.5 h-1.5 bg-[#ffd96d]/80 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                  </div>
                </div>
              </motion.div>
            )}
          </div>
        </div>
      )}

      {/* Background Teal Glow (Visible mainly in empty state) */}
      <div className={`absolute bottom-0 left-0 right-0 h-[58%] pointer-events-none transition-opacity duration-700 ${isEmpty ? 'opacity-100' : 'opacity-30'} bg-[radial-gradient(ellipse_at_bottom,_rgba(255,217,109,0.55),_rgba(255,112,88,0.18),_transparent_70%)] blur-3xl`} />

      {/* Input Area */}
      <div className={`absolute left-0 right-0 z-20 transition-all duration-500 ease-[cubic-bezier(0.2,0.8,0.2,1)] ${isEmpty ? 'bottom-14 px-14' : 'bottom-0 px-5 pb-5 pt-10 bg-gradient-to-t from-[var(--app-bg)] via-[var(--app-bg)] to-transparent'}`}>
        <AnimatePresence>
          {selectedCapture && (
            <motion.div
              initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 10 }}
              className={`mb-3 ml-2 flex items-center gap-2 bg-black/50 backdrop-blur-md border border-white/10 rounded-lg p-1.5 pr-3 w-max shadow-lg ${isEmpty ? 'ml-0' : ''}`}
            >
              <div className="h-10 w-16 rounded overflow-hidden bg-black/50">
                <img src={convertFileSrc(selectedCapture.path)} alt="context" className="w-full h-full object-cover" />
              </div>
              <div className="flex flex-col">
                <span className="text-xs font-medium text-white/90">Context Attached</span>
                <span className="text-[10px] text-white/50 truncate max-w-[150px]">{selectedCapture.filename}</span>
              </div>
              <button onClick={() => selectCapture(null)} className="ml-2 p-1 rounded-full hover:bg-white/10 text-white/50 hover:text-white">
                <X size={14} />
              </button>
            </motion.div>
          )}
        </AnimatePresence>

        <form onSubmit={handleSubmit} className="relative flex items-center shadow-2xl">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="What are you looking for..."
            className="w-full bg-white border border-black/[0.05] hover:border-black/[0.09] focus:border-[var(--accent)]/45 focus:ring-1 focus:ring-[var(--accent)]/35 rounded-[30px] py-[18px] pl-6 pr-16 text-[var(--ink)] placeholder:text-[var(--muted)]/70 outline-none transition-all font-medium text-[15px] shadow-[0_18px_45px_rgba(60,52,41,0.12)]"
            autoFocus
          />
          <button
            type="submit"
            disabled={!input.trim() || isLoading}
            className="absolute right-2 p-2.5 rounded-full bg-[var(--accent)] text-white hover:bg-[var(--accent-hover)] disabled:opacity-50 disabled:bg-[#dad7cf] disabled:text-black/30 transition-colors shadow-md"
          >
            <Send size={18} fill="currentColor" className="ml-0.5" />
          </button>
        </form>
      </div>

      {/* Sidebar */}
      <HistorySidebar />

      {/* Settings Modal */}
      <AnimatePresence>
        {isSettingsOpen && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="absolute inset-0 z-50 flex items-center justify-center bg-[#f7f5ef]/70 backdrop-blur-sm"
            onClick={() => setIsSettingsOpen(false)}
          >
            <motion.div
              initial={{ scale: 0.95, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.95, opacity: 0 }}
              onClick={(e) => e.stopPropagation()}
              className="bg-white/95 border border-black/[0.06] rounded-3xl p-6 w-[320px] shadow-2xl relative"
            >
              <button
                onClick={() => setIsSettingsOpen(false)}
                className="absolute top-4 right-4 text-[var(--muted)] hover:text-[var(--ink)]"
              >
                <X size={16} />
              </button>
              <h3 className="text-lg font-semibold text-[var(--ink)] mb-4 flex items-center gap-2">
                <Settings size={18} className="text-[var(--accent)]" />
                Settings
              </h3>

              <div className="space-y-4">
                <div className="bg-[#f4f1ea] border border-black/[0.04] p-4 rounded-2xl">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-sm font-medium text-[var(--ink)]">Processing Mode</span>
                    <button
                      onClick={toggleGpu}
                      className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${useGpu ? 'bg-[#71E3E6]' : 'bg-white/20'}`}
                    >
                      <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${useGpu ? 'translate-x-6' : 'translate-x-1'}`} />
                    </button>
                  </div>

                  <div className="flex gap-2 p-1 bg-white rounded-xl text-xs mt-3">
                    <div className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-lg transition-colors ${!useGpu ? 'bg-[#f4f1ea] text-[var(--ink)]' : 'text-[var(--muted)]'}`}>
                      <Cpu size={12} />
                      CPU
                    </div>
                    <div className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-lg transition-colors ${useGpu ? 'bg-[#f4f1ea] text-[var(--ink)]' : 'text-[var(--muted)]'}`}>
                      <Zap size={12} />
                      GPU
                    </div>
                  </div>
                  <p className="text-[10px] text-[var(--muted)] mt-2 leading-tight">
                    {useGpu
                      ? "GPU mode requires CUDA toolkit installed. Faster but uses VRAM."
                      : "CPU mode is slower but works on any machine without setup."}
                  </p>
                </div>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Sync Panel */}
      <AnimatePresence>
        {isSyncOpen && <SyncPanel onClose={() => setIsSyncOpen(false)} />}
      </AnimatePresence>

      {/* Memory Vault Panel */}
      <AnimatePresence>
        {isVaultOpen && <MemoryVaultPanel onClose={() => setIsVaultOpen(false)} />}
      </AnimatePresence>
    </motion.div>
  );
}
