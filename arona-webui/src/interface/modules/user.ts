export interface User {
  id: string;
  username: string;
  config: Record<string, Record<string, string>>;
}
