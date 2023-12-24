import { defineStore } from "pinia";
import { BaseStoreState } from "./type";
import { User } from "@/interface";

const useBaseStore = defineStore({
  id: "common",
  state: (): BaseStoreState => ({
    token: "",
    // @ts-ignore
    user: {},
  }),
  getters: {
    userId(state) {
      return state.user.id;
    },
  },
  actions: {
    setToken(token: string) {
      this.token = token;
    },
    setUser(user: User) {
      this.user = user;
    },
  },
  persist: {
    key: "base",
  },
});
export default useBaseStore;
