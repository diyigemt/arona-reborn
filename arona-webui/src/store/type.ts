export interface BaseStoreState {
}

export interface SettingStoreState {
  theme: {
    themeType: string;
    themeColor: string | number;
  };
}
