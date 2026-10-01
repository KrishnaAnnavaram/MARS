import type { ReactNode } from 'react';
import { useRouter } from '@tanstack/react-router';

/** In-app link for server-provided hrefs (e.g. attention items): client-side navigation, no reload. */
export function HrefLink({ href, children, className, title }: { href: string; children: ReactNode; className?: string; title?: string }) {
  const router = useRouter();
  return (
    <a
      href={href}
      title={title}
      className={className}
      onClick={(e) => {
        if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        e.preventDefault();
        void router.navigate({ href });
      }}
    >
      {children}
    </a>
  );
}
