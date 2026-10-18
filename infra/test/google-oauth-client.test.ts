import { webClientCredentials } from '../lambda/google-oauth-client';

describe('webClientCredentials', () => {
  it('reads Web application client_secret.json shape', () => {
    const creds = webClientCredentials({
      web: {
        client_id: '295257233255-iqrdnab7db4thkk2u65i5u37pdcuctd1.apps.googleusercontent.com',
        client_secret: 'test-secret',
        redirect_uris: ['http://localhost'],
      },
    });
    expect(creds).toEqual({
      clientId: '295257233255-iqrdnab7db4thkk2u65i5u37pdcuctd1.apps.googleusercontent.com',
      clientSecret: 'test-secret',
    });
  });

  it('returns undefined when web block is missing secrets', () => {
    expect(webClientCredentials({ web: { client_id: 'x' } })).toBeUndefined();
    expect(webClientCredentials({})).toBeUndefined();
  });
});
