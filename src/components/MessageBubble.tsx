import { Message } from '../types';
import { motion } from 'framer-motion';
import { Bot, User } from 'lucide-react';
import { convertFileSrc } from '@tauri-apps/api/core';

interface MessageBubbleProps {
  message: Message;
}

export function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user';
  
  // Check if this is a search result
  const isSearchResult = message.content.startsWith('SEARCH_RESULTS:');
  const isRagResult = message.content.startsWith('RAG_RESULT:');
  
  let searchResults = [];
  let displayContent = message.content;
  
  if (isSearchResult) {
    try {
      searchResults = JSON.parse(message.content.substring(15));
      displayContent = "Here are the most relevant captures I found:";
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
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className={`flex gap-3 max-w-[85%] ${isUser ? 'ml-auto flex-row-reverse' : ''}`}
    >
      <div className={`flex-shrink-0 w-8 h-8 flex items-center justify-center rounded-full border shadow-sm
        ${isUser 
          ? 'bg-[var(--accent)] border-white text-white' 
          : 'bg-white border-black/[0.05] text-[var(--muted)]'}`}
      >
        {isUser ? <User size={16} /> : <Bot size={16} />}
      </div>
      
      <div className="flex flex-col gap-2 w-full">
        <div className={`p-4 rounded-2xl text-[15px] leading-relaxed shadow-sm
          ${isUser 
            ? 'bg-[var(--accent)] text-white rounded-tr-sm inline-block self-end' 
            : 'bg-white border border-black/[0.04] text-[var(--ink)] rounded-tl-sm backdrop-blur-md inline-block self-start'}`}
        >
          {displayContent}
        </div>
        
        {(isSearchResult || isRagResult) && searchResults.length > 0 && (
          <div className="flex flex-col gap-3 mt-2 w-[400px]">
            {searchResults.map((res: any, idx: number) => (
              <div key={idx} className="flex gap-3 bg-white border border-black/[0.05] rounded-2xl p-2 hover:bg-[#fbfaf6] transition-colors cursor-pointer shadow-sm">
                <div className="w-24 h-16 rounded-xl overflow-hidden bg-[#e8e5dc] shrink-0">
                  <img src={convertFileSrc(res.path)} alt="Result" className="w-full h-full object-cover" />
                </div>
                <div className="flex flex-col justify-center overflow-hidden">
                  <p className="text-sm text-[var(--ink)] line-clamp-2 leading-snug">{res.text}</p>
                  <p className="text-[10px] text-[var(--accent)] mt-1 font-medium">
                    Relevance: {Math.max(0, Math.min(100, Math.round((1.0 / (1.0 + res.score)) * 100)))}%
                  </p>
                </div>
              </div>
            ))}
          </div>
        )}
        
        {message.imageUrl && !isSearchResult && !isRagResult && (
          <div className={`rounded-2xl overflow-hidden border border-black/[0.06] shadow-md ${isUser ? 'self-end' : 'self-start'} max-w-xs`}>
            <img 
              src={convertFileSrc(message.imageUrl)} 
              alt="Attached capture" 
              className="w-full h-auto object-cover"
              loading="lazy"
            />
          </div>
        )}
      </div>
    </motion.div>
  );
}
