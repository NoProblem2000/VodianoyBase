import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { CheckCircle2, AlertTriangle, X } from 'lucide-react';
import { useI18n } from '../i18n';

type ToastKind = 'success' | 'error';
interface Toast {
  id: number;
  kind: ToastKind;
  text: string;
}

interface ToastApi {
  success: (text: string) => void;
  error: (text: string) => void;
}

const ToastContext = createContext<ToastApi>({ success: () => undefined, error: () => undefined });

export function useToast(): ToastApi {
  return useContext(ToastContext);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const { t: tr } = useI18n();
  const [toasts, setToasts] = useState<Toast[]>([]);

  const dismiss = useCallback((id: number) => setToasts((all) => all.filter((t) => t.id !== id)), []);
  const push = useCallback(
    (kind: ToastKind, text: string) => {
      const id = Date.now() + Math.random();
      setToasts((all) => [...all.slice(-3), { id, kind, text }]);
      if (kind === 'success') window.setTimeout(() => dismiss(id), 5000);
    },
    [dismiss],
  );

  const value: ToastApi = {
    success: (text) => push('success', text),
    error: (text) => push('error', text),
  };

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[min(520px,calc(100vw-2rem))] flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            role={t.kind === 'error' ? 'alert' : 'status'}
            className={`pointer-events-auto flex items-start gap-2.5 rounded-md border bg-kraken-raised px-3 py-2.5 shadow-xl ${
              t.kind === 'error' ? 'border-red-800' : 'border-teal-700'
            }`}
          >
            {t.kind === 'error' ? (
              <AlertTriangle size={16} className="mt-0.5 shrink-0 text-red-400" />
            ) : (
              <CheckCircle2 size={16} className="mt-0.5 shrink-0 text-teal-400" />
            )}
            <span className="flex-1 whitespace-pre-wrap break-words text-[13px] leading-snug">{t.text}</span>
            <button className="text-gray-500 hover:text-gray-200" onClick={() => dismiss(t.id)} aria-label={tr('common.dismiss')}>
              <X size={14} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
