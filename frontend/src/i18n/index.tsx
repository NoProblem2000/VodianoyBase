import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import en from './en.json';
import ru from './ru.json';
import { loadSetting, saveSetting } from '../lib/storage';

/**
 * Lightweight typed i18n: two JSON dictionaries, a React context and no runtime dependencies.
 * - t('topBar.export') — keys are checked by TypeScript;
 * - t('x', { name }) — {{name}} placeholders;
 * - tn('plural.commits', 3) — plural forms one / few / many / other chosen by Intl.PluralRules;
 * - rich('key', { name: <b>…</b> }) — placeholders replaced with React nodes.
 */
export type Lang = 'ru' | 'en';
export const LANGS: Lang[] = ['ru', 'en'];
export const STORAGE_KEY = 'pgvc_lang';
const DEFAULT_LANG: Lang = 'ru';

type Dict = typeof en;
// both dictionaries must have exactly the same keys: a missing or extra key is a compile error
const RU: Dict = ru;
const EN: typeof ru = en;
const DICTS: Record<Lang, Dict> = { en: EN, ru: RU };

type PluralForms = { one: string; few: string; many: string; other: string };
type Join<P extends string, K extends string> = P extends '' ? K : `${P}.${K}`;
type TextKeys<T, P extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? Join<P, K> : T[K] extends PluralForms ? never : TextKeys<T[K], Join<P, K>>;
}[keyof T & string];
type PluralKeys<T, P extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? never : T[K] extends PluralForms ? Join<P, K> : PluralKeys<T[K], Join<P, K>>;
}[keyof T & string];

export type TKey = TextKeys<Dict>;
export type PKey = PluralKeys<Dict>;
export type Vars = Record<string, string | number | null | undefined>;

// language used by the API client for Accept-Language (outside React)
let currentLang: Lang = initialLang();

export function getLang(): Lang {
  return currentLang;
}

function initialLang(): Lang {
  const stored = loadSetting(STORAGE_KEY, DEFAULT_LANG);
  return (LANGS as string[]).includes(stored) ? (stored as Lang) : DEFAULT_LANG;
}

function lookup(dict: Dict, key: string): unknown {
  let node: unknown = dict;
  for (const part of key.split('.')) {
    if (node === null || typeof node !== 'object') return undefined;
    node = (node as Record<string, unknown>)[part];
  }
  return node;
}

function interpolate(template: string, vars?: Vars): string {
  if (!vars) return template;
  return template.replace(/\{\{(\w+)\}\}/g, (all, name: string) => {
    const v = vars[name];
    return v === undefined || v === null ? all : String(v);
  });
}

export interface I18n {
  lang: Lang;
  setLang: (lang: Lang) => void;
  t: (key: TKey, vars?: Vars) => string;
  tn: (key: PKey, count: number, vars?: Vars) => string;
  rich: (key: TKey, nodes: Record<string, ReactNode>) => ReactNode;
  /** BCP 47 locale for dates and numbers */
  locale: string;
}

function makeI18n(lang: Lang, setLang: (lang: Lang) => void): I18n {
  const dict = DICTS[lang];
  const plural = new Intl.PluralRules(lang);
  const text = (key: string): string => {
    const value = lookup(dict, key) ?? lookup(DICTS.en, key);
    return typeof value === 'string' ? value : key;
  };
  return {
    lang,
    setLang,
    locale: lang === 'ru' ? 'ru-RU' : 'en-US',
    t: (key, vars) => interpolate(text(key), vars),
    tn: (key, count, vars) => {
      const forms = (lookup(dict, key) ?? lookup(DICTS.en, key)) as Partial<PluralForms> | undefined;
      const form = plural.select(count) as keyof PluralForms;
      const template = forms?.[form] ?? forms?.other ?? key;
      return interpolate(template, { count, ...vars });
    },
    rich: (key, nodes) => {
      const parts = text(key).split(/(\{\{\w+\}\})/g);
      return parts.map((part, i) => {
        const m = /^\{\{(\w+)\}\}$/.exec(part);
        if (m && m[1] in nodes) return <span key={i}>{nodes[m[1]]}</span>;
        return part;
      });
    },
  };
}

const I18nContext = createContext<I18n>(makeI18n(currentLang, () => undefined));

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(currentLang);
  const setLang = useCallback((next: Lang) => {
    currentLang = next;
    saveSetting(STORAGE_KEY, next);
    setLangState(next);
  }, []);
  useEffect(() => {
    currentLang = lang;
    document.documentElement.lang = lang;
  }, [lang]);
  const value = useMemo(() => makeI18n(lang, setLang), [lang, setLang]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18n {
  return useContext(I18nContext);
}
