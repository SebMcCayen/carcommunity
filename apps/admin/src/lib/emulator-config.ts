/** Development emulators must be an isolated demo stack, never mixed with cloud Auth. */
export function validateEmulatorConfig(
  projectId: string,
  hosts: { auth?: string; firestore?: string; functions?: string },
  production: boolean,
): void {
  const values = [hosts.auth, hosts.firestore, hosts.functions];
  if (production || !values.some(Boolean)) return;
  if (!projectId.startsWith('demo-') || values.some((host) => !host)) {
    throw new Error(
      'Local admin testing requires a demo-* project and Auth, Firestore and Functions emulators together.',
    );
  }
  for (const host of values) {
    const match = /^(localhost|127\.0\.0\.1):([1-9]\d{0,4})$/.exec(host!);
    if (!match || Number(match[2]) > 65535)
      throw new Error('Admin emulators must use an explicit loopback host and valid port.');
  }
}
