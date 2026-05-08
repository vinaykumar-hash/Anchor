export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  imageUrl?: string;
  timestamp: number;
}

export interface Capture {
  path: string;
  filename: string;
  timestamp: number;
}
