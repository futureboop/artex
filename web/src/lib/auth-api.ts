// Keep the public login route independent of the full application API bundle.
export async function authStatus(): Promise<{ initialized: boolean }> {
  const response = await fetch("/api/auth/status");
  if (!response.ok) throw new Error(`auth status: ${response.status}`);
  return response.json();
}

export async function login(password: string): Promise<{ token: string }> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "ARTEX", password }),
  });
  if (!response.ok) throw new Error(`login: ${response.status}`);
  return response.json();
}
