import { useState, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useAppStore } from '../store/useAppStore';
import { MessageBubble } from './MessageBubble';
import { HistorySidebar } from './HistorySidebar';
import { invoke, convertFileSrc } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { Send, History, Sparkles, X, Settings, Cpu, Zap } from 'lucide-react';

export function ChatInterface() {
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
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

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.95, y: 20 }}
      animate={{ opacity: 1, scale: 1, y: 0 }}
      exit={{ opacity: 0, scale: 0.95, y: 20 }}
      transition={{ duration: 0.2, ease: "easeOut" }}
      className="glass-panel w-full h-full rounded-2xl overflow-hidden flex flex-col relative shadow-[0_0_40px_rgba(0,0,0,0.8)] border border-white/10"
    >
      {/* Top Drag Region & Header */}
      <div
        data-tauri-drag-region
        className="h-12 border-b border-white/10 flex items-center justify-between px-5 bg-white/5 cursor-move"
      >
        <div className="flex items-center gap-2 pointer-events-none text-white/90">
          <Sparkles size={16} className="text-[var(--accent)]" />
          <span className="font-semibold text-sm tracking-wide">ContextMemory</span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setIsSettingsOpen(true)}
            className={`p-1.5 rounded-md transition-colors ${isSettingsOpen ? 'bg-white/20 text-white' : 'hover:bg-white/10 text-white/60 hover:text-white'}`}
            title="Settings"
          >
            <Settings size={16} />
          </button>
          <button
            onClick={toggleHistory}
            className={`p-1.5 rounded-md transition-colors ${isHistoryOpen ? 'bg-white/20 text-white' : 'hover:bg-white/10 text-white/60 hover:text-white'}`}
            title="Toggle History (Alt+H)"
          >
            <History size={16} />
          </button>
          <button
            onClick={() => invoke('toggle_chat_window')}
            className="p-1.5 rounded-md hover:bg-white/10 text-white/60 hover:text-white transition-colors ml-2"
            title="Close (Esc)"
          >
            <X size={16} />
          </button>
        </div>
      </div>

      {/* Main Chat Area */}
      <div className="flex-1 overflow-hidden relative flex">
        <div className="flex-1 flex flex-col h-full relative">

          {/* Messages Scroll Area */}
          <div
            ref={scrollRef}
            className="flex-1 overflow-y-auto p-5 space-y-6 no-scrollbar pb-32"
          >
            {messages.map((msg) => (
              <MessageBubble key={msg.id} message={msg} />
            ))}

            {isLoading && (
              <motion.div
                initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}
                className="flex gap-3 max-w-[85%]"
              >
                <div className="w-8 h-8 rounded-full bg-white/5 border border-white/10 flex items-center justify-center text-[var(--accent)]">
                  <Sparkles size={14} className="animate-pulse" />
                </div>
                <div className="bg-white/5 border border-white/5 p-3.5 rounded-2xl rounded-tl-sm backdrop-blur-md">
                  <div className="flex gap-1.5">
                    <span className="w-1.5 h-1.5 bg-white/40 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                    <span className="w-1.5 h-1.5 bg-white/40 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                    <span className="w-1.5 h-1.5 bg-white/40 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                  </div>
                </div>
              </motion.div>
            )}
          </div>

          {/* Input Area (Absolute positioned at bottom) */}
          <div className="absolute bottom-0 left-0 right-0 p-4 bg-gradient-to-t from-[var(--glass-bg)] via-[var(--glass-bg)] to-transparent pt-10">
            {/* Context Attachment Indicator */}
            <AnimatePresence>
              {selectedCapture && (
                <motion.div
                  initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 10 }}
                  className="mb-3 ml-2 flex items-center gap-2 bg-black/50 backdrop-blur-md border border-[var(--accent)]/50 rounded-lg p-1.5 pr-3 w-max shadow-lg"
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

            <form onSubmit={handleSubmit} className="relative flex items-center">
              <input
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Ask about your screen..."
                className="w-full bg-black/40 border border-white/10 hover:border-white/20 focus:border-[var(--accent)] focus:ring-1 focus:ring-[var(--accent)] rounded-xl py-3.5 pl-4 pr-12 text-white/90 placeholder:text-white/30 outline-none transition-all shadow-inner font-medium text-[15px]"
                autoFocus
              />
              <button
                type="submit"
                disabled={!input.trim() || isLoading}
                className="absolute right-2 p-2 rounded-lg bg-[var(--accent)] text-white hover:bg-[var(--accent-hover)] disabled:opacity-50 disabled:hover:bg-[var(--accent)] transition-colors"
              >
                <Send size={16} />
              </button>
            </form>
          </div>
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
              className="absolute inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
              onClick={() => setIsSettingsOpen(false)}
            >
              <motion.div
                initial={{ scale: 0.95, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                exit={{ scale: 0.95, opacity: 0 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-[var(--glass-bg)] border border-white/10 rounded-2xl p-6 w-[320px] shadow-2xl relative"
              >
                <button
                  onClick={() => setIsSettingsOpen(false)}
                  className="absolute top-4 right-4 text-white/50 hover:text-white"
                >
                  <X size={16} />
                </button>
                <h3 className="text-lg font-semibold text-white mb-4 flex items-center gap-2">
                  <Settings size={18} className="text-[var(--accent)]" />
                  Settings
                </h3>

                <div className="space-y-4">
                  <div className="bg-white/5 border border-white/5 p-4 rounded-xl">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm font-medium text-white/90">Processing Mode</span>
                      <button
                        onClick={toggleGpu}
                        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${useGpu ? 'bg-[var(--accent)]' : 'bg-white/20'}`}
                      >
                        <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${useGpu ? 'translate-x-6' : 'translate-x-1'}`} />
                      </button>
                    </div>

                    <div className="flex gap-2 p-1 bg-black/30 rounded-lg text-xs mt-3">
                      <div className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-md transition-colors ${!useGpu ? 'bg-white/10 text-white' : 'text-white/50'}`}>
                        <Cpu size={12} />
                        CPU
                      </div>
                      <div className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-md transition-colors ${useGpu ? 'bg-white/10 text-white' : 'text-white/50'}`}>
                        <Zap size={12} />
                        GPU
                      </div>
                    </div>
                    <p className="text-[10px] text-white/40 mt-2 leading-tight">
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
      </div>
    </motion.div>
  );
}
