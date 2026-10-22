import type { GlossaryStatus } from './types';

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  return new Intl.DateTimeFormat('fa-IR-u-ca-gregory', {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  }).format(date);
}

export function statusText(status: GlossaryStatus | 'DELETED' | string): string {
  return ({
    CANDIDATE: 'در انتظار بررسی',
    APPROVED: 'تأیید شده',
    REJECTED: 'رد شده',
    DELETED: 'حذف شده',
  } as Record<string, string>)[status] ?? status;
}

export function Status({ value }: { value: string }) {
  const level = value === 'APPROVED' ? 'approved'
    : value === 'REJECTED' || value === 'DELETED' ? 'rejected' : 'candidate';
  return <span className={`status ${level}`}>{statusText(value)}</span>;
}

export function Paging({ page, size, total, change }: {
  page: number;
  size: number;
  total: number;
  change: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  return <div className="pagination">
    <span className="meta">{total.toLocaleString('fa-IR')} مورد · صفحه {(page + 1).toLocaleString('fa-IR')} از {pages.toLocaleString('fa-IR')}</span>
    <div className="pager-actions">
      <button type="button" className="btn secondary" disabled={page === 0} onClick={() => change(page - 1)}>قبلی</button>
      <button type="button" className="btn secondary" disabled={page + 1 >= pages} onClick={() => change(page + 1)}>بعدی</button>
    </div>
  </div>;
}
