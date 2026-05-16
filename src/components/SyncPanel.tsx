import { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { invoke } from '@tauri-apps/api/core';
import { X, RefreshCw, Wifi, Monitor, Smartphone, Loader2 } from 'lucide-react';

interface SyncPanelProps {
  onClose: () => void;
}

interface SyncStatus {
  ip: string;
  port: number;
  entryCount: number;
  pairedIp?: string;
  autoSyncEnabled?: boolean;
}

export function SyncPanel({ onClose }: SyncPanelProps) {
  const [syncStatus, setSyncStatus] = useState<SyncStatus | null>(null);
  const [manualIp, setManualIp] = useState('');
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncResult, setSyncResult] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [autoSyncEnabled, setAutoSyncEnabled] = useState(true);

  useEffect(() => {
    fetchSyncStatus();
  }, []);

  const fetchSyncStatus = async () => {
    setIsLoading(true);
    try {
      const resultStr = await invoke<string>('get_sync_status');
      const result = JSON.parse(resultStr);
      setSyncStatus({
        ip: result.ip || 'Unknown',
        port: result.port || 8473,
        entryCount: result.entryCount || 0,
        pairedIp: result.pairedIp,
        autoSyncEnabled: result.autoSyncEnabled !== false
      });
      setAutoSyncEnabled(result.autoSyncEnabled !== false);
    } catch (e) {
      console.error('Failed to get sync status:', e);
    }
    setIsLoading(false);
  };

  const handleManualSync = async () => {
    if (!manualIp.trim()) return;
    setIsSyncing(true);
    setSyncResult(null);

    // Strip port if user entered it (e.g. "192.168.1.7:8473" → "192.168.1.7")
    const rawInput = manualIp.trim();
    const ip = rawInput.includes(':') ? rawInput.split(':')[0] : rawInput;
    const port = 8473;

    try {
      // Call our Rust bridge which calls Python
      // Python handles the HTTP requests to bypass browser CSP/fetch blocks
      const resultStr = await invoke<string>('sync_with', { ip, port });
      const result = JSON.parse(resultStr);

      if (result.status === 'success') {
        setSyncResult(
          `✅ Sync complete! Imported: ${result.imported || 0}, Exported: ${result.exported || 0}, Skipped: ${result.skipped || 0}`
        );
        fetchSyncStatus();
      } else {
        setSyncResult(`❌ Sync failed: ${result.message || 'Unknown error'}`);
      }
    } catch (e: any) {
      setSyncResult(`❌ Sync failed: ${e.message || e}`);
    }
    setIsSyncing(false);
  };

  const handleToggleAutoSync = async () => {
    const newState = !autoSyncEnabled;
    setAutoSyncEnabled(newState);
    try {
      await invoke('toggle_auto_sync', { enabled: newState });
    } catch (e) {
      console.error('Failed to toggle auto sync:', e);
      // Revert if failed
      setAutoSyncEnabled(!newState);
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      className="absolute inset-0 z-50 flex items-center justify-center bg-[#f7f5ef]/70 backdrop-blur-sm"
      onClick={onClose}
    >
      <motion.div
        initial={{ scale: 0.95, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        exit={{ scale: 0.95, opacity: 0 }}
        onClick={(e) => e.stopPropagation()}
        className="bg-white/95 border border-black/[0.06] rounded-3xl p-6 w-[380px] shadow-2xl relative"
      >
        <button
          onClick={onClose}
          className="absolute top-4 right-4 text-[var(--muted)] hover:text-[var(--ink)]"
        >
          <X size={16} />
        </button>

        <h3 className="text-lg font-semibold text-[var(--ink)] mb-4 flex items-center gap-2">
          <Wifi size={18} className="text-[var(--accent)]" />
          Device Sync
        </h3>

        {/* This Device */}
        <div className="bg-[#f4f1ea] border border-black/[0.04] p-4 rounded-2xl mb-4">
          <div className="flex items-center gap-2 mb-1">
            <Monitor size={14} className="text-[var(--accent)]" />
            <span className="text-xs font-medium text-[var(--muted)]">This PC</span>
          </div>
          {isLoading ? (
            <div className="flex items-center gap-2 text-[var(--muted)] text-sm">
              <Loader2 size={14} className="animate-spin" />
              Loading...
            </div>
          ) : syncStatus ? (
            <div>
              <p className="text-sm text-[var(--ink)] font-medium">
                {syncStatus.ip}:{syncStatus.port}
              </p>
              <div className="flex justify-between items-end">
                <p className="text-xs text-[var(--muted)]">
                  {syncStatus.entryCount} memories stored
                </p>
                {syncStatus.pairedIp && (
                  <div className="flex flex-col items-end">
                    <span className="text-[10px] text-[var(--accent)] font-bold uppercase tracking-wider">Paired Device</span>
                    <span className="text-[10px] text-[var(--muted)]">{syncStatus.pairedIp}</span>
                  </div>
                )}
              </div>
            </div>
          ) : (
            <p className="text-sm text-[var(--muted)]">Failed to get status</p>
          )}
        </div>

        {/* Auto Sync Toggle */}
        <div className="flex items-center justify-between bg-[#f4f1ea] border border-black/[0.04] p-3 rounded-2xl mb-4">
          <div className="flex flex-col">
            <span className="text-sm font-medium text-[var(--ink)]">Auto Sync</span>
            <span className="text-[10px] text-[var(--muted)]">Automatically sync with paired device</span>
          </div>
          <button 
            onClick={handleToggleAutoSync}
            className={`w-10 h-5 rounded-full relative transition-colors ${autoSyncEnabled ? 'bg-[var(--accent)]' : 'bg-white/20'}`}
          >
            <div className={`absolute top-0.5 bottom-0.5 w-4 rounded-full bg-white transition-transform ${autoSyncEnabled ? 'left-[22px]' : 'left-0.5'}`} />
          </button>
        </div>

        {/* Manual Connect */}
        <div className="mb-4">
          <div className="flex items-center gap-2 mb-2">
            <Smartphone size={14} className="text-[var(--accent)]" />
            <span className="text-xs font-medium text-[var(--muted)]">
              Connect to Device
            </span>
          </div>
          <div className="flex gap-2">
            <input
              type="text"
              value={manualIp}
              onChange={(e) => setManualIp(e.target.value)}
              placeholder="e.g. 192.168.1.7"
              className="flex-1 bg-white border border-black/[0.06] focus:border-[var(--accent)] rounded-xl py-2 px-3 text-sm text-[var(--ink)] placeholder:text-[var(--muted)]/60 outline-none transition-all"
            />
            <button
              onClick={handleManualSync}
              disabled={isSyncing || !manualIp.trim()}
              className="px-4 py-2 rounded-xl bg-[var(--accent)] text-white text-sm font-medium hover:bg-[var(--accent-hover)] disabled:opacity-50 transition-colors flex items-center gap-2"
            >
              {isSyncing ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <RefreshCw size={14} />
              )}
              Sync
            </button>
          </div>
          <p className="text-[10px] text-[var(--muted)] mt-1.5">
            Enter the IP shown on the Android app's Sync screen
          </p>
        </div>

        {/* Sync Result */}
        {syncResult && (
          <div
            className={`p-3 rounded-lg text-sm ${
              syncResult.startsWith('✅')
                ? 'bg-green-500/10 border border-green-500/20 text-green-300'
                : 'bg-red-500/10 border border-red-500/20 text-red-300'
            }`}
          >
            {syncResult}
          </div>
        )}
      </motion.div>
    </motion.div>
  );
}
