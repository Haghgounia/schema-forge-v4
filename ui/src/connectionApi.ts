import {fetchJson} from './transport.ts';

export type ConnectionSummary = {
  platform: string;
  displayName: string;
  enabled: boolean;
  jdbcUrl: string;
  username: string;
  driverClassName: string;
  passwordConfigured: boolean;
  driverAvailable: boolean;
};
export type ConnectionProbe = {
  platform: string;
  jdbcUrl: string;
  username: string;
  password: string;
  useConfiguredPassword: boolean;
};
export type ConnectionProbeResult = {
  success: boolean;
  code: string;
  message: string;
  product: string;
  version: string;
  elapsedMillis: number;
};
export const connectionApi = {
  defaults: (adminKey: string) => fetchJson<ConnectionSummary[]>('/api/v1/connections', {connectionKey: adminKey}),
  test: (adminKey: string, input: ConnectionProbe) =>
    fetchJson<ConnectionProbeResult>('/api/v1/connections/test', {
      method: 'POST', json: input, connectionKey: adminKey,
    }),
};

/** Export *non-secret* Spring YAML to be applied by an operator at the next restart. */
export function metadataYaml(platform: string, jdbcUrl: string, username: string, enabled: boolean): string {
  if (!/^(oracle|postgresql|sqlserver|mysql|mariadb|db2luw|db2zos)$/.test(platform)) {
    throw new Error('DBMS نامعتبر است');
  }
  if (/[\r\n]/.test(jdbcUrl + username)) throw new Error('مقدار چندخطی مجاز نیست');
  if (/[;?&](?:password|pwd|user|uid)\s*=/i.test(jdbcUrl) || /:\/\/[^/;?]+:[^/;?]+@/.test(jdbcUrl)) {
    throw new Error('اطلاعات ورود را در JDBC URL قرار ندهید');
  }
  return [
    '# Non-secret Spring Boot configuration snippet; review before merging into application.yml.',
    '# Set SCHEMAFORGE_METADATA_' + platform.toUpperCase() + '_PASSWORD privately on the Java server.',
    'schemaforge:',
    '  metadata:',
    `    ${platform}:`,
    `      enabled: ${enabled}`,
    `      url: ${JSON.stringify(jdbcUrl)}`,
    `      username: ${JSON.stringify(username)}`,
    '',
  ].join('\n');
}
