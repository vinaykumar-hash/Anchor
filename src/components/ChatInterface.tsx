import { useState, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useAppStore } from '../store/useAppStore';
import { MessageBubble } from './MessageBubble';
import { HistorySidebar } from './HistorySidebar';
import { SyncPanel } from './SyncPanel';
import { MemoryVaultPanel } from './MemoryVaultPanel';
import { invoke, convertFileSrc } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { Send, Sparkles, X, Settings, Cpu, Wifi, Database, Search, ArrowLeft } from 'lucide-react';
import { Capture } from '../types';
import logoImg from '../logo/Main.png';

export function ChatInterface() {
  const [input, setInput] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isSyncOpen, setIsSyncOpen] = useState(false);
  const [isVaultOpen, setIsVaultOpen] = useState(false);
  const [isHomeView, setIsHomeView] = useState(true);
  const [homeCaptures, setHomeCaptures] = useState<Capture[]>([]);
  const scrollRef = useRef<HTMLDivElement>(null);

  const { messages, addMessage, updateMessage, selectedCapture, selectCapture, toggleHistory, isHistoryOpen, useGpu, toggleGpu } = useAppStore();
  const currentAssistantMsgId = useRef<string | null>(null);
  const currentAssistantContent = useRef<string>('');

  // Load captures for the home grid (supports search query filtering)
  useEffect(() => {
    if (!searchQuery.trim()) {
      loadHomeCaptures();
      return;
    }

    const delayDebounceFn = setTimeout(async () => {
      try {
        const resultStr = await invoke<string>('search_context', { query: searchQuery.trim() });
        const resultJson = JSON.parse(resultStr);
        if (resultJson.results) {
          const searchedCaps = resultJson.results.map((r: any) => ({
            path: r.path,
            filename: r.path.split(/[/\\]/).pop() || '',
            timestamp: 0
          }));
          setHomeCaptures(searchedCaps);
        }
      } catch (e) {
        console.error('Failed to search context:', e);
      }
    }, 300);

    return () => clearTimeout(delayDebounceFn);
  }, [searchQuery, isHomeView]);

  const loadHomeCaptures = async () => {
    try {
      const caps = await invoke<Capture[]>('get_captures');
      setHomeCaptures(caps || []);
    } catch (e) {
      console.error('Failed to load captures:', e);
    }
  };

  useEffect(() => {
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
    invoke('set_gpu_mode', { useGpu });
  }, [messages, isLoading, useGpu]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || isLoading) return;

    const userMsg = input.trim();
    setInput('');
    setIsHomeView(false);

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
      } else {
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
        const resultJson = JSON.parse(resultStr);
        if (resultJson.answer) {
          updateMessage(assistantId, `RAG_RESULT:${JSON.stringify({ answer: resultJson.answer, sources: resultJson.sources })}`);
        } else {
          updateMessage(assistantId, "I couldn't process your question properly.");
        }
      }
    } catch (e) {
      console.error(e);
    } finally {
      setIsLoading(false);
      currentAssistantMsgId.current = null;
    }
  };

  const handleCaptureClick = (cap: Capture) => {
    selectCapture(cap);
    setIsHomeView(false);
  };

  return (
    <div className="flex flex-col h-full bg-black text-white overflow-hidden">

      {/* ─── Persistent Header ─── */}
      <header className="shrink-0 pt-6 pb-0">
        <div className="flex items-center justify-between mb-4 px-6" data-tauri-drag-region>
          <div className="flex items-center gap-3">
            {!isHomeView && (
              <button
                onClick={() => setIsHomeView(true)}
                className="p-2 rounded-full hover:bg-white/10 transition-colors"
              >
                <ArrowLeft size={20} />
              </button>
            )}
            <img src={logoImg} className="w-8 h-8 object-contain rounded-lg" alt="Anchor Logo" data-tauri-drag-region />
            <h1 className="text-3xl font-bold tracking-tight text-white leading-none" data-tauri-drag-region>
              Anchor
            </h1>
          </div>

          {/* 3-line menu button */}
          <button
            onClick={() => setIsSettingsOpen(!isSettingsOpen)}
            className="w-10 h-10 rounded-full bg-[#2C2C2E] flex items-center justify-center hover:bg-[#3A3A3C] transition-colors"
          >
            <div className="flex flex-col gap-[4px]">
              <span className="block w-[18px] h-[2px] rounded-full bg-white/60" />
              <span className="block w-[18px] h-[2px] rounded-full bg-white/60" />
              <span className="block w-[18px] h-[2px] rounded-full bg-white/60" />
            </div>
          </button>
        </div>

        {/* Search Bar */}
        <div className="relative mb-3 px-6">
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search"
            className="w-full bg-[#1C1C1E] border-none rounded-3xl px-5 py-3 text-base text-white placeholder-white/40 focus:outline-none focus:ring-1 focus:ring-[var(--aqua)]/30"
          />
          <Search size={18} className="absolute right-10 top-1/2 -translate-y-1/2 text-white/30" />
        </div>

        {/* Gradient Bar — full width, no padding */}
        <div className="gradient-bar" />
      </header>

      {/* ─── Settings Dropdown ─── */}
      <AnimatePresence>
        {isSettingsOpen && (
          <>
            {/* Backdrop to close */}
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="fixed inset-0 z-40"
              onClick={() => setIsSettingsOpen(false)}
            />
            <motion.div
              initial={{ opacity: 0, y: -10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              className="absolute right-4 top-16 z-50 w-56 rounded-2xl p-2 shadow-2xl"
              style={{
                background: 'rgba(44, 44, 46, 0.55)',
                backdropFilter: 'blur(24px) saturate(180%)',
                WebkitBackdropFilter: 'blur(24px) saturate(180%)',
                border: '1px solid rgba(255, 255, 255, 0.12)',
                boxShadow: '0 8px 32px rgba(0, 0, 0, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.06)',
              }}
            >
              <button
                onClick={() => { setIsSyncOpen(true); setIsSettingsOpen(false); }}
                className="w-full flex items-center gap-3 px-4 py-3 rounded-xl text-white/70 hover:bg-white/5 hover:text-white transition-all text-sm font-medium"
              >
                <Wifi size={16} className="text-[var(--aqua)]" /> Sync Devices
              </button>
              <button
                onClick={() => { setIsVaultOpen(true); setIsSettingsOpen(false); }}
                className="w-full flex items-center gap-3 px-4 py-3 rounded-xl text-white/70 hover:bg-white/5 hover:text-white transition-all text-sm font-medium"
              >
                <Database size={16} className="text-[var(--aqua)]" /> Memory Vault
              </button>
              <div className="w-full h-px bg-white/5 my-1" />
              <button
                onClick={toggleGpu}
                className={`w-full flex items-center gap-3 px-4 py-3 rounded-xl transition-all text-sm font-medium ${useGpu ? 'text-[var(--aqua)] bg-[var(--aqua)]/10' : 'text-white/70 hover:bg-white/5 hover:text-white'}`}
              >
                <Cpu size={16} /> {useGpu ? 'GPU Mode' : 'CPU Mode'}
              </button>
              <button
                onClick={() => { toggleHistory(); setIsSettingsOpen(false); }}
                className="w-full flex items-center gap-3 px-4 py-3 rounded-xl text-white/70 hover:bg-white/5 hover:text-white transition-all text-sm font-medium"
              >
                <Settings size={16} className="text-white/50" /> Capture History
              </button>
            </motion.div>
          </>
        )}
      </AnimatePresence>

      {/* ─── Main Content ─── */}
      <main className="flex-1 relative overflow-hidden flex flex-col">
        <AnimatePresence mode="wait">
          {isHomeView ? (
            <motion.div
              key="home"
              initial={{ opacity: 0, y: 15 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.98 }}
              transition={{ duration: 0.4, ease: [0.2, 0.8, 0.2, 1] }}
              className="w-full h-full px-4 py-4 flex flex-col gap-6 overflow-y-auto custom-scrollbar pb-28"
            >
              {/* Collections – Horizontal Scroll
              <section>
                <h2 className="text-lg font-semibold text-white mb-4">Collection</h2>
                <div className="flex gap-3 overflow-x-auto no-scrollbar pb-2">
                  {[
                    { name: 'Work', info: 'Professional captures', icon: '💼', color: 'var(--aqua)' },
                    { name: 'Web Inspiration', info: 'Design references', icon: '🌍', color: 'var(--pink)' },
                    { name: 'Research', info: 'Articles and papers', icon: '📑', color: 'var(--green)' },
                    { name: 'Projects', info: 'Code and builds', icon: '🚀', color: 'var(--red)' },
                  ].map((col) => (
                    <motion.div 
                      key={col.name}
                      whileHover={{ y: -3, scale: 1.02 }}
                      className="flex-shrink-0 w-64 p-5 rounded-2xl bg-[#1C1C1E] border border-white/5 hover:border-white/10 transition-all cursor-pointer"
                    >
                      <div className="flex items-center gap-4">
                        <div className="w-20 h-20 rounded-2xl bg-[#2C2C2E] flex items-center justify-center text-2xl">
                          {col.icon}
                        </div>
                        <div>
                          <h3 className="text-base font-bold text-white">{col.name}</h3>
                          <p className="text-white/40 text-sm mt-1">{col.info}</p>
                        </div>
                      </div>
                    </motion.div>
                  ))}
                </div>
              </section> */}

              {/* All Memories – Actual captures from backend */}
              <section className="flex-1">
                <h2 className="text-lg font-semibold text-white mb-8 text-center mt-4">All Memories</h2>
                {homeCaptures.length > 0 ? (
                  <div className="grid grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2">
                    {homeCaptures.map((cap, i) => (
                      <motion.div
                        key={cap.path}
                        initial={{ opacity: 0, scale: 0.95 }}
                        animate={{ opacity: 1, scale: 1 }}
                        transition={{ delay: i * 0.03 }}
                        whileHover={{ scale: 1.03 }}
                        onClick={() => handleCaptureClick(cap)}
                        className="group relative aspect-square rounded-sm bg-[#1C1C1E] border border-white/5 hover:border-white/15 transition-all cursor-pointer overflow-hidden"
                      >
                        <img
                          src={convertFileSrc(cap.path)}
                          alt={cap.filename}
                          className="w-full h-full object-cover"
                          loading="lazy"
                        />
                        {/* Glass overlay on hover */}
                        <div
                          className="absolute inset-0 flex flex-col justify-end p-3 opacity-0 group-hover:opacity-100 transition-opacity duration-300"
                          style={{
                            background: 'linear-gradient(to top, rgba(0,0,0,0.6) 0%, rgba(0,0,0,0.2) 50%, transparent 100%)',
                          }}
                        >
                          <div
                            className="rounded-xl px-3 py-2"
                            style={{
                              background: 'rgba(255, 255, 255, 0.1)',
                              backdropFilter: 'blur(16px)',
                              WebkitBackdropFilter: 'blur(16px)',
                              border: '1px solid rgba(255, 255, 255, 0.15)',
                            }}
                          >
                            <p className="text-white text-xs font-semibold truncate leading-tight">
                              {cap.filename.replace(/\.[^/.]+$/, '')}
                            </p>
                            <p className="text-white/50 text-[10px] mt-0.5 font-medium">
                              {cap.timestamp
                                ? new Date(cap.timestamp * 1000).toLocaleString(undefined, {
                                  month: 'short',
                                  day: 'numeric',
                                  hour: '2-digit',
                                  minute: '2-digit',
                                })
                                : 'Captured'}
                            </p>
                          </div>
                        </div>
                      </motion.div>
                    ))}
                  </div>
                ) : (
                  <div className="grid grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
                    {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((i) => (
                      <div
                        key={i}
                        className="aspect-square rounded-2xl bg-[#1C1C1E] border border-white/5"
                      >
                        <div className="w-full h-full bg-gradient-to-br from-white/5 to-transparent" />
                      </div>
                    ))}
                    <p className="col-span-full text-center text-white/30 text-sm mt-4">
                      No memories yet. Press Alt+S to capture your screen.
                    </p>
                  </div>
                )}
              </section>

              {/* Ask Questions Button – Bottom */}
              <div className="fixed bottom-10 left-1/2 -translate-x-1/2 z-30">
                <motion.button
                  whileHover={{ scale: 1.05, y: -2 }}
                  whileTap={{ scale: 0.98 }}
                  onClick={() => setIsHomeView(false)}
                  className="flex items-center gap-3 px-8 py-4 rounded-full text-white font-bold text-base transition-all"
                  style={{
                    background: 'rgba(255, 255, 255, 0.08)',
                    backdropFilter: 'blur(20px)',
                    WebkitBackdropFilter: 'blur(20px)',
                    border: '1px solid rgba(255, 255, 255, 0.18)',
                    boxShadow: '0 8px 32px rgba(111, 209, 215, 0.18), inset 0 1px 0 rgba(255, 255, 255, 0.12)',
                  }}
                >
                  {/* <Sparkles size={18} className="text-[var(--aqua)]" /> */}
                  Ask questions
                </motion.button>
              </div>
            </motion.div>
          ) : (
            <motion.div
              key="chat"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="w-full h-full flex flex-col"
            >
              {/* Chat Thread */}
              <div
                ref={scrollRef}
                className="flex-1 overflow-y-auto px-4 pt-8 scroll-smooth custom-scrollbar pb-10"
              >
                <div className="max-w-3xl mx-auto space-y-10">
                  {messages.filter(m => m.id !== 'welcome').length === 0 ? (
                    <div className="h-full flex flex-col items-center justify-center text-center pt-24">
                      <h2 className="text-4xl font-bold text-white mb-4 tracking-tight">How can I help you remember?</h2>
                      <p className="text-white/30 text-lg max-w-md font-medium leading-relaxed">Search through your digital life, find lost moments, and ask questions about anything you've seen.</p>

                      <div className="mt-12 grid grid-cols-2 gap-3 max-w-2xl w-full">
                        {[
                          "Recap my last project meeting",
                          "Where did I see that design reference?",
                          "What was the code snippet I copied?",
                          "Find screenshots of my travel plan"
                        ].map(suggestion => (
                          <button
                            key={suggestion}
                            onClick={() => { setInput(suggestion); }}
                            className="p-5 rounded-2xl bg-[#1C1C1E] border border-white/5 text-white/50 text-sm font-semibold hover:bg-white/[0.05] hover:text-white hover:border-white/10 transition-all text-left"
                          >
                            {suggestion}
                          </button>
                        ))}
                      </div>
                    </div>
                  ) : (
                    messages.filter(m => m.id !== 'welcome').map((msg) => (
                      <MessageBubble key={msg.id} message={msg} />
                    ))
                  )}
                  {isLoading && (
                    <div className="flex flex-col items-center gap-4 py-8">
                      <p className="text-white/40 text-sm font-semibold">Thinking...</p>
                      <div className="flex gap-1">
                        <div className="w-5 h-5 rounded-full bg-[#FF7890] animate-pulse" />
                        <div className="w-5 h-5 rounded-full bg-[#B86CDE] animate-pulse" style={{ animationDelay: '150ms' }} />
                        <div className="w-5 h-5 rounded-full bg-[var(--aqua)] animate-pulse" style={{ animationDelay: '300ms' }} />
                      </div>
                    </div>
                  )}
                </div>
              </div>

              {/* Chat Input Area */}
              <div className="px-4 pb-6 pt-3">
                <div className="max-w-3xl mx-auto">
                  <form
                    onSubmit={handleSubmit}
                    className="flex items-end gap-3"
                  >
                    <div className="flex-1 relative">
                      {selectedCapture && (
                        <div className="absolute -top-14 left-0 flex items-center gap-2 px-3 py-2 bg-[#1C1C1E] border border-white/10 rounded-2xl">
                          <div className="w-8 h-8 rounded-lg overflow-hidden border border-white/10">
                            <img src={convertFileSrc(selectedCapture.path)} className="w-full h-full object-cover" />
                          </div>
                          <span className="text-[10px] font-bold text-[var(--aqua)] uppercase tracking-wider">Context</span>
                          <button onClick={() => selectCapture(null)} className="p-1 hover:bg-white/10 rounded-full text-white/40 hover:text-white transition-colors">
                            <X size={12} />
                          </button>
                        </div>
                      )}
                      <input
                        type="text"
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        placeholder="Ask questions"
                        className="w-full bg-[#1C1C1E] border-none rounded-3xl px-6 py-4 text-base text-white placeholder-white/40 focus:outline-none focus:ring-1 focus:ring-[var(--aqua)]/30"
                        autoFocus
                      />
                    </div>

                    <button
                      type="submit"
                      disabled={!input.trim() || isLoading}
                      className="w-12 h-12 rounded-full bg-[#16484B] flex items-center justify-center disabled:opacity-30 transition-all hover:scale-105 active:scale-95 shrink-0"
                    >
                      <Send size={18} className="text-[var(--aqua)]" />
                    </button>
                  </form>
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </main>

      {/* Panels */}
      <AnimatePresence>
        {isSyncOpen && <SyncPanel onClose={() => setIsSyncOpen(false)} />}
        {isVaultOpen && <MemoryVaultPanel onClose={() => setIsVaultOpen(false)} />}
      </AnimatePresence>

      <HistorySidebar />
    </div>
  );
}
