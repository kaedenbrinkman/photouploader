/** Google Cloud downloaded client_secret.json (Web or legacy installed shape). */
export interface GoogleOAuthClientJson {
  web?: { client_id?: string; client_secret?: string; redirect_uris?: string[] };
  installed?: { client_id?: string; client_secret?: string };
}

export function webClientCredentials(json: GoogleOAuthClientJson): {
  clientId: string;
  clientSecret: string;
} | undefined {
  const clientId = json.web?.client_id ?? json.installed?.client_id;
  const clientSecret = json.web?.client_secret ?? json.installed?.client_secret;
  if (!clientId || !clientSecret) return undefined;
  return { clientId, clientSecret };
}
