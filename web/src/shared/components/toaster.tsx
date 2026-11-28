import { createPortal } from 'react-dom'
import type { CSSProperties } from 'react'
import { Toaster as Sonner } from 'sonner'
import { getPortalContainer } from '@/shared/lib/portal-container'
import { CENTER_TOASTER_ID } from '@/shared/lib/toast'

export function Toaster() {
  const node = (
    <div translate="no">
      <Sonner
        id={CENTER_TOASTER_ID}
        position="top-center"
        className="!left-1/2 !right-auto !top-4 !-translate-x-1/2 !z-[100]"
        offset={16}
        mobileOffset={16}
        style={{ '--border-radius': 'var(--radius)' } as CSSProperties}
        toastOptions={{
          toasterId: CENTER_TOASTER_ID,
          classNames: {
            toast: 'glass-strong mx-auto w-fit max-w-[min(100vw-2rem,32rem)] border border-border/40',
            title: 'text-foreground font-semibold text-center',
            description: 'text-muted-foreground text-center',
            content: 'w-full text-center',
            actionButton: 'bg-primary text-primary-foreground',
            cancelButton: 'bg-muted text-muted-foreground',
            error: 'border-destructive/40',
            success: 'border-success/40',
            warning: 'border-warning/40',
            info: 'border-info/40',
          },
        }}
      />
    </div>
  )

  const host = typeof document !== 'undefined' ? getPortalContainer() : undefined
  if (host) {
    return createPortal(node, host)
  }
  return node
}
