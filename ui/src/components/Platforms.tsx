import { PLATFORMS } from '../coreApi';

/** Mirrors RestPlatformSelection: 'all' is exclusive; backend validates required selection. */
export default function Platforms({ selected, onChange }: { selected: string[]; onChange: (value: string[]) => void }) {
  function toggle(value: string, enabled: boolean) {
    if (value === 'all') { onChange(enabled ? ['all'] : []); return; }
    const next = selected.filter(item => item !== 'all' && item !== value);
    if (enabled) next.push(value);
    onChange(next);
  }
  return <fieldset className="platforms">
    <legend>DBMS هدف <span className="required">*</span></legend>
    <label><input type="checkbox" checked={selected.includes('all')}
      onChange={e => toggle('all', e.target.checked)} /> همه (۷ DBMS)</label>
    {PLATFORMS.map(([value, title]) => <label key={value}>
      <input type="checkbox" checked={selected.includes(value)}
        onChange={e => toggle(value, e.target.checked)} /> {title}
    </label>)}
  </fieldset>;
}
