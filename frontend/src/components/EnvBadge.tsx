interface Props {
  name: string;
  color: string;
  active?: boolean;
  title?: string;
}

export function EnvBadge({ name, color, active, title }: Props) {
  return (
    <span
      className={active ? 'env-badge active' : 'env-badge'}
      style={{ ['--env' as string]: color }}
      title={title ?? name}
    >
      <span className="env-flag" aria-hidden="true" />
      {name}
    </span>
  );
}
