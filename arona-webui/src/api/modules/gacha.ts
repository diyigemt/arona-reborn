import service from "@/api/http";
import { Student } from "@/interface";

// eslint-disable-next-line import/prefer-default-export
export const GachaApi = {
  fetchStudents() {
    return service
      .raw<Student[]>({
        url: "/gacha/students",
        method: "GET",
      })
      .then((res) => {
        res.data = res.data.map((it) => {
          const r = it.rarity as unknown as number;
          if (r === 0) {
            it.rarity = "R";
          } else if (r === 1) {
            it.rarity = "SR";
          } else if (r === 2) {
            it.rarity = "SSR";
          }
          return it;
        });
        res.data.sort();
        return res;
      });
  },
};
