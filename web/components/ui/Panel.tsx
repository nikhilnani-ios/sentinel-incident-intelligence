import clsx from "clsx";
import type { ReactNode } from "react";

export function Panel({
  title,
  actions,
  children,
  className,
  flush,
}: {
  title?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  flush?: boolean;
}) {
  return (
    <section className={clsx("panel", className)}>
      {title && (
        <header className="panel-heading">
          <h2 className="label">{title}</h2>
          {actions}
        </header>
      )}
      <div className={flush ? "" : "p-4"}>{children}</div>
    </section>
  );
}
