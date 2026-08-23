export interface User {
  id: string;
  username: string;
  // 在后端主配置 superAdminUid 里 (机器人部署者); 只用来决定显示什么, 权限由后端把关
  superAdmin?: boolean;
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
