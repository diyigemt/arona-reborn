export interface BaseStoreState {
  token: string;
}

export interface SettingStoreState {
  theme: {
    themeType: string;
    themeColor: string | number;
  };
}
