import { PLATFORMS } from '../coreApi';

/** Mirrors RestPlatformSelection: 'all' is exclusive; backend validates required selection. */
export default function Platforms({ selected, onChange, lockedTo }: {
  selected: string[]; onChange: (value: string[]) => void; lockedTo?: string;
}) {
  function toggle(value: string, enabled: boolean) {
    if (lockedTo && value !== lockedTo) return;
    if (value === 'all') { onChange(enabled ? ['all'] : []); return; }
    const next = selected.filter(item => item !== 'all' && item !== value);
    if (enabled) next.push(value);
    onChange(next);
  }
  return <fieldset className="platforms">
    {lockedTo && <p className="hint">Active metadata connection: {lockedTo}. Disconnect to use another DBMS.</p>}
    <legend>DBMS هدف <span className="required">*</span></legend>
    <label><input type="checkbox" checked={selected.includes('all')}
      disabled={Boolean(lockedTo)} onChange={e => toggle('all', e.target.checked)} /> همه (۷ DBMS)</label>
    {PLATFORMS.map(([value, title]) => <label key={value}>
      <input type="checkbox" disabled={Boolean(lockedTo && lockedTo !== value)} checked={selected.includes(value)}
        onChange={e => toggle(value, e.target.checked)} /> {title}
    </label>)}
  </fieldset>;
}
