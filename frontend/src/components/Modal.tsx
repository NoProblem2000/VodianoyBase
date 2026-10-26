import { useEffect, type ReactNode } from 'react';
import { X } from 'lucide-react';

interface Props {
  title: string;
  subtitle?: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  /** Tailwind max-width class */
  width?: string;
}

export function Modal({ title, subtitle, onClose, children, footer, width = 'max-w-3xl' }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`flex max-h-[calc(100vh-2rem)] w-full ${width} flex-col overflow-hidden rounded-lg border border-kraken-line bg-kraken-panel shadow-2xl`}
      >
        <header className="flex items-start justify-between gap-4 border-b border-kraken-line px-5 py-3.5">
          <div>
            <h2 className="text-[15px] font-semibold text-gray-100">{title}</h2>
            {subtitle && <p className="mt-0.5 text-xs text-gray-400">{subtitle}</p>}
          </div>
          <button className="btn btn-ghost h-7 px-1.5" onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-auto px-5 py-4">{children}</div>
        {footer && <footer className="flex items-center gap-2 border-t border-kraken-line px-5 py-3">{footer}</footer>}
      </div>
    </div>
  );
}
