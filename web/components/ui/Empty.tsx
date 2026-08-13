export function Empty({ message, hint }: { message: string; hint?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-1 py-12 text-center">
      <p className="font-mono text-xs uppercase tracking-[0.14em] text-muted">{message}</p>
      {hint && <p className="max-w-sm text-sm text-muted/70">{hint}</p>}
    </div>
  );
}
