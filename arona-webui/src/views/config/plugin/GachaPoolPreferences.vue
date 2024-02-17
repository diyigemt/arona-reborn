<template>
  <ElTabs v-model="tab">
    <ElTabPane name="custom" label="自定义">
      <PluginPreferenceForm
        v-model:form="gachaPoolConfig"
        :default-form="defaultGachaPoolConfig"
        p-id="com·diyigemt·arona"
        p-key="GachaConfig"
      >
        <template #default="{ from }">
          <div v-if="from !== 'contact'" class="mb-16px">
            <div class="w-100% justify-end">
              <ElButton type="primary" @click="onCreate">新建</ElButton>
            </div>
            <ElTable :data="gachaPoolConfig.pools">
              <ElTableColumn label="卡池名称" prop="name" />
              <ElTableColumn label="pickup" prop="pickup">
                <template #default="{ row }">
                  <div class="white-space" style="white-space: pre">
                    {{ pickups(row.pickup) }}
                  </div>
                </template>
              </ElTableColumn>
              <ElTableColumn label="rate" prop="rate">
                <template #default="{ row }">
                  {{ rates(row.rate) }}
                </template>
              </ElTableColumn>
              <ElTableColumn label="pickupRate" prop="pickupRate" width="200">
                <template #default="{ row }">
                  {{ rates(row.pickupRate) }}
                </template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="160">
                <template #default="{ row }">
                  <ElButton @click="onEdit(row)">编辑</ElButton>
                  <ElButton type="danger" @click="onDelete(row)">删除</ElButton>
                </template>
              </ElTableColumn>
            </ElTable>
          </div>
        </template>
      </PluginPreferenceForm>
    </ElTabPane>
  </ElTabs>
  <ElDialog v-model="showGachaEditDialog" :close-on-click-modal="false" append-to-body width="500px">
    <ElForm
      ref="form"
      :model="gachaPool"
      label-width="110"
      label-position="left"
      :rules="formRule"
      class="hide-required"
    >
      <ElFormItem label="卡池名称:" prop="name">
        <ElInput v-model="gachaPool.name" class="w-240px!" />
      </ElFormItem>
      <ElFormItem label="pickup:" prop="pickup">
        <ElSelect v-model="gachaPool.pickup" filterable multiple :filter-method="onFilter" class="w-240px!">
          <ElOption v-for="(e, index) in filterStudents" :key="index" :value="e.id" :label="e.name">
            <div class="flex justify-between">
              <span>{{ e.name }}</span>
              <span>{{ studentState(e) }}</span>
            </div>
          </ElOption>
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="出货率:">
        <div>
          <div>
            <span class="w-40px inline-block">R:</span>
            <ElInputNumber
              v-model="gachaPool.rate.r"
              :step="0.1"
              step-strictly
              :min="0"
              :max="100"
              @change="onEditRate"
            ></ElInputNumber>
            <span class="ml-8px">%</span>
          </div>
          <div class="mt-8px">
            <span class="w-40px inline-block">SR:</span>
            <ElInputNumber
              v-model="gachaPool.rate.sr"
              :max="srRateMax"
              :step="0.1"
              step-strictly
              :min="0"
              @change="onEditRate"
            ></ElInputNumber>
            <span class="ml-8px">%</span>
          </div>
          <div class="mt-8px">
            <span class="w-40px inline-block">SSR:</span>
            <ElInputNumber
              v-model="gachaPool.rate.ssr"
              disabled
              :step="0.1"
              step-strictly
              :min="0"
              :max="100"
            ></ElInputNumber>
            <span class="ml-8px">%</span>
          </div>
        </div>
      </ElFormItem>
      <ElFormItem label="pickup出货率:">
        <div>
          <div class="mt-8px">
            <span class="w-40px inline-block">SR:</span>
            <ElInputNumber
              v-model="gachaPool.pickupRate.sr"
              :step="0.1"
              step-strictly
              :min="0"
              :max="rate.sr"
            ></ElInputNumber>
            <span class="ml-8px">%</span>
          </div>
          <div class="mt-8px">
            <span class="w-40px inline-block">SSR:</span>
            <ElInputNumber
              v-model="gachaPool.pickupRate.ssr"
              :step="0.1"
              step-strictly
              :min="0"
              :max="rate.ssr"
            ></ElInputNumber>
            <span class="ml-8px">%</span>
          </div>
        </div>
      </ElFormItem>
    </ElForm>
    <template #footer>
      <div class="text-right">
        <ElButton type="primary" @click="onConfirm">确认</ElButton>
        <ElButton @click="showGachaEditDialog = false">取消</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { FormRules, FormValidateCallback } from "element-plus";
import { Ref } from "vue";
import PluginPreferenceForm from "@/components/plugin/PluginPreferenceForm.vue";
import { Student } from "@/interface";
import { GachaApi } from "@/api/modules/gacha";
import { IConfirm, warningMessage } from "@/utils/message";

defineOptions({
  name: "GachaPoolPreferences",
});
interface GachaPoolConfig {
  pools: CustomPool[];
}
interface CustomPool {
  name: string;
  pickup: number[];
  rate: GachaRate;
  pickupRate: GachaPickupRate;
}
interface GachaPickupRate {
  sr: number;
  ssr: number;
}
interface GachaRate extends GachaPickupRate {
  r: number;
}
const tab = ref<"custom">("custom");
type F = {
  validate(callback?: FormValidateCallback): Promise<void>;
};
const form = ref<F>() as Ref<F>;
const defaultGachaPoolConfig: GachaPoolConfig = {
  pools: [],
};
const students = ref<Student[]>([]);
const filterStudents = ref<Student[]>([]);
const showGachaEditDialog = ref(false);
const gachaPool = ref<CustomPool>({
  name: "",
  pickup: [],
  rate: {
    r: 78.5,
    sr: 18.5,
    ssr: 3,
  },
  pickupRate: {
    sr: 3,
    ssr: 0.7,
  },
});
const rate = computed(() => gachaPool.value.rate);
const gachaPoolConfig = ref<GachaPoolConfig>(defaultGachaPoolConfig);
let isPoolCreate = false;
function onCreate() {
  if (gachaPoolConfig.value.pools.length >= 5) {
    warningMessage("只支持配置最高5个");
    return;
  }
  gachaPool.value = {
    name: "",
    pickup: [],
    rate: {
      r: 78.5,
      sr: 18.5,
      ssr: 3,
    },
    pickupRate: {
      sr: 3,
      ssr: 0.7,
    },
  };
  isPoolCreate = true;
  showGachaEditDialog.value = true;
}
function onConfirm() {
  form.value.validate((res) => {
    if (!res) {
      return;
    }
    if (isPoolCreate) {
      gachaPoolConfig.value.pools.push(gachaPool.value);
    } else {
      const idx = gachaPoolConfig.value.pools.findIndex((it) => it.name === gachaPool.value.name);
      gachaPoolConfig.value.pools.splice(idx, 1, gachaPool.value);
    }
    showGachaEditDialog.value = false;
  });
}
function onEdit(pool: CustomPool) {
  gachaPool.value = pool;
  isPoolCreate = false;
  showGachaEditDialog.value = true;
}
function onDelete(pool: CustomPool) {
  IConfirm("警告", `确定要删除卡池: ${pool.name}?`).then(() => {
    const idx = gachaPoolConfig.value.pools.findIndex((it) => it.name === pool.name);
    gachaPoolConfig.value.pools.splice(idx, 1);
  });
}
function onEditRate() {
  gachaPool.value.rate.ssr = Number((100 - rate.value.r - rate.value.sr).toFixed(1));
  if (gachaPool.value.pickupRate.sr > rate.value.sr) {
    gachaPool.value.pickupRate.sr = rate.value.sr;
  }
  if (gachaPool.value.pickupRate.ssr > rate.value.ssr) {
    gachaPool.value.pickupRate.ssr = rate.value.ssr;
  }
}
function doFilter(input: string) {
  if (input === "限定" || input === "限") {
    return students.value.filter((it) => it.limit === "Unique");
  }
  if (input === "常驻" || input === "常") {
    return students.value.filter((it) => it.limit === "Permanent");
  }
  if (input === "活动" || input === "活") {
    return students.value.filter((it) => it.limit === "Event");
  }
  if (input === "3" || input === "三") {
    return students.value.filter((it) => it.rarity === "SSR");
  }
  if (input === "2" || input === "二") {
    return students.value.filter((it) => it.rarity === "SR");
  }
  if (input === "1" || input === "一") {
    return students.value.filter((it) => it.rarity === "R");
  }
  return students.value.filter((it) => it.name.includes(input));
}
function onFilter(input: string) {
  filterStudents.value = doFilter(input);
}
const srRateMax = computed(() => Number((100 - rate.value.r).toFixed(1)));
function pickups(id: number[]) {
  return students_(students.value.filter((it) => id.includes(it.id)));
}
function rates(r: GachaRate) {
  const base = `sr: ${r.sr.toFixed(1)}%,ssr: ${r.ssr.toFixed(1)}%`;
  if (r.r) {
    return `r: ${r.r.toFixed(1)}%,${base}`;
  }
  return base;
}
function students_(s: Student[]) {
  return s
    .map((it) => {
      return `${it.name}(${studentState(it)})`;
    })
    .join("\n");
}
function studentState(s: Student) {
  let star = "⭐";
  let limit = "常驻";
  if (s.rarity === "SR") {
    star = "⭐⭐";
  } else if (s.rarity === "SSR") {
    star = "⭐⭐⭐";
  }
  if (s.limit === "Unique") {
    limit = "限定";
  } else if (s.limit === "Event") {
    limit = "活动";
  }
  return `${limit}-${star}`;
}
onMounted(() => {
  GachaApi.fetchStudents().then((res) => {
    students.value = res.data;
    filterStudents.value = students.value;
  });
});
const formRule: FormRules = {
  name: {
    required: true,
    message: "需要卡池名称",
  },
};
</script>

<style lang="scss" scoped></style>
