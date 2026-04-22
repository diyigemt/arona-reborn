export interface User {
  id: string;
  username: string;
}

export interface CustomMenuButton {
  label: string;
  data: string;
  enter: boolean;
}
export interface CustomMenuRow {
  buttons: CustomMenuButton[];
}
export interface CustomMenuConfig {
  rows: CustomMenuRow[];
}
