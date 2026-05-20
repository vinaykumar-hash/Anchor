import { useState } from 'react';
import { Message } from '../types';
import { motion } from 'framer-motion';
import { convertFileSrc } from '@tauri-apps/api/core';
import { ChevronRight, ChevronDown } from 'lucide-react';

interface MessageBubbleProps {
  message: Message;
}

export function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user';
  const [showReferences, setShowReferences] = useState(false);

  // Check if this is a search result or RAG result
  const isSearchResult = message.content.startsWith('SEARCH_RESULTS:');
  const isRagResult = message.content.startsWith('RAG_RESULT:');

  let searchResults: any[] = [];
  let displayContent = message.content;

  if (isSearchResult) {
    try {
      searchResults = JSON.parse(message.content.substring(15));
      displayContent = "I found these relevant memories from your history:";
    } catch (e) {
      displayContent = "Error parsing search results.";
    }
  } else if (isRagResult) {
    try {
      const parsed = JSON.parse(message.content.substring(11));
      displayContent = parsed.answer;
      searchResults = parsed.sources || [];
    } catch (e) {
      displayContent = "Error parsing AI response.";
    }
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 12, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      className="flex flex-col items-center w-full py-3"
    >
      {isUser ? (
        /* ─── User Message: Dark pill, centered ─── */
        <div className="inline-block bg-[#1C1C1E] rounded-3xl px-7 py-3 max-w-[80%]">
          <p className="text-white text-[15px] font-semibold text-center leading-relaxed">
            {displayContent}
          </p>
        </div>
      ) : (
        /* ─── AI Response: Centered text with Show References ─── */
        <div className="flex flex-col items-center w-full max-w-2xl">
          {displayContent && (
            <p className="text-white/90 text-base leading-relaxed text-center px-4">
              {displayContent}
            </p>
          )}

          {/* Show References toggle */}
          {(searchResults.length > 0 || isSearchResult || isRagResult) && (
            <button
              onClick={() => setShowReferences(!showReferences)}
              className="mt-4 text-[var(--aqua)] text-[15px] font-bold hover:opacity-80 transition-opacity flex items-center gap-1"
            >
              {showReferences ? (
                <>Hide References <ChevronDown size={16} /></>
              ) : (
                <>Show References <ChevronRight size={16} /></>
              )}
            </button>
          )}

          {/* Expanded reference cards */}
          {showReferences && searchResults.length > 0 && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              exit={{ opacity: 0, height: 0 }}
              className="mt-4 flex gap-3 overflow-x-auto no-scrollbar w-full justify-center"
            >
              {searchResults.map((res: any, idx: number) => (
                <div 
                  key={idx} 
                  className="flex-shrink-0 w-28 h-40 rounded-xl bg-[#626D6E] overflow-hidden border border-white/10 cursor-pointer hover:scale-105 transition-transform"
                >
                  <img 
                    src={convertFileSrc(res.path)} 
                    alt="Reference" 
                    className="w-full h-full object-cover" 
                  />
                </div>
              ))}
            </motion.div>
          )}

          {/* Attached image */}
          {message.imageUrl && !isSearchResult && !isRagResult && (
            <div className="mt-4 rounded-2xl overflow-hidden border border-white/10 max-w-sm cursor-pointer hover:scale-[1.02] transition-transform">
              <img
                src={convertFileSrc(message.imageUrl)}
                alt="Attached capture"
                className="w-full h-auto object-cover"
                loading="lazy"
              />
            </div>
          )}
        </div>
      )}
    </motion.div>
  );
}
