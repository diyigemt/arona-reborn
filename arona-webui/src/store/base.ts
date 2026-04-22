import { defineStore } from "pinia";
import { BaseStoreState } from "./type";
import { User } from "@/interface";

const useBaseStore = defineStore({
  id: "common",
  state: (): BaseStoreState => ({
    token: "",
    // @ts-ignore
    user: {},
    clarity: false,
  }),
  getters: {
    userId(state) {
      return state.user.id;
    },
  },
  actions: {
    setClarity(clarity: boolean) {
      this.clarity = clarity;
    },
    setToken(token: string) {
      this.token = token;
    },
    setUser(user: User) {
      this.user = user;
    },
  },
  // 仅持久化 user 字段; token 留在内存, 页面刷新后必须重新登录, 避免 XSS 通过 localStorage 拿走 token.
  persist: {
    key: "base",
    paths: ["user"],
  },
});
export default useBaseStore;
