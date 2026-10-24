import {fetchJson, fetchResponse} from './transport.ts';

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
  useConfiguredPassword: false;
  schema: string;
};
export type ConnectionProbeResult = {
  success: boolean;
  code: string;
  message: string;
  product: string;
  version: string;
  elapsedMillis: number;
  defaultSchema?: string;
  availableSchemas?: string[];
  schemaListTruncated?: boolean;
};
export type ActiveMetadataConnection = {
  token: string;
  platform: string;
  verifiedSchema: string;
  expiresAt: string;
  defaultSchema?: string;
  availableSchemas?: string[];
  schemaListTruncated?: boolean;
};

/** Connection selection is user-driven, not a global or administrator-only setting. */
export const connectionApi = {
  defaults: () => fetchJson<ConnectionSummary[]>('/api/v1/connections'),
  test: (input: ConnectionProbe) =>
    fetchJson<ConnectionProbeResult>('/api/v1/connections/test', {method:'POST', json:input}),
  activate: (input: ConnectionProbe) =>
    fetchJson<ActiveMetadataConnection>('/api/v1/connections/activate', {method:'POST', json:input}),
  status: (token: string) =>
    fetchJson<ActiveMetadataConnection>('/api/v1/connections/active', {metadataToken:token}),
  disconnect: async (token: string) => {
    await fetchResponse('/api/v1/connections/active', {method:'DELETE', metadataToken:token});
  },
};
