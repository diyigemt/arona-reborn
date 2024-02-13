<template>
  <ElTabs v-model="tab">
    <ElTabPane name="trainer" label="攻略设置">
      <PluginPreferenceForm
        v-model:form="trainerConfig"
        :default-form="defaultTrainerConfig"
        :data-processor="onParseTrainerOption"
        :post-data-processor="onSaveParseTrainerOption"
        p-id="com·diyigemt·arona"
        p-key="TrainerConfig"
      >
        <ElFormItem label="别名覆盖：" prop="override">
          <ElButton type="primary" @click="onAddOverride">新增</ElButton>
          <ElTable :data="trainerOverrideConfig">
            <ElTableColumn type="index" label="序号" width="100" />
            <ElTableColumn prop="name" label="原始值">
              <template #default="{ row }">
                <div v-if="row.edit">
                  <ElSelect v-model="row.name" filterable multiple allow-create collapse-tags default-first-option />
                </div>
                <div v-else>
                  {{ row.name }}
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="name" label="覆盖值" width="300">
              <template #default="{ row }">
                <div v-if="row.edit">
                  <ElSelect
                    v-model="row.value"
                    filterable
                    remote
                    default-first-option
                    :remote-method="onSearchTrainer"
                    :loading="trainerSearchLoading"
                  >
                    <ElOption
                      v-for="(e, index) in aronaTrainerOptions"
                      :key="index"
                      :value="e.value"
                      :label="e.label"
                    />
                  </ElSelect>
                </div>
                <div v-else>
                  {{ row.value }}
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="type" label="类型" width="100">
              <template #default="{ row }">
                <div v-if="row.edit">
                  <ElSelect v-model="row.type">
                    <ElOption value="RAW" label="RAW" />
                  </ElSelect>
                </div>
                <div v-else>
                  {{ row.type }}
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="160">
              <template #default="{ row }">
                <div v-if="row.edit">
                  <ElButton type="primary" @click="onConfirm(row)">确认</ElButton>
                  <ElButton @click="onCancel(row)">取消</ElButton>
                </div>
                <div v-else>
                  <ElButton @click="onEdit(row)">编辑</ElButton>
                  <ElButton type="danger" @click="onDeleteOverride(row)">删除</ElButton>
                </div>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElFormItem>
      </PluginPreferenceForm>
    </ElTabPane>
    <ElTabPane name="tarot" label="塔罗牌设置">
      <PluginPreferenceForm
        v-model:form="tarotConfig"
        :default-form="defaultTarotConfig"
        p-id="com·diyigemt·arona"
        p-key="TarotConfig"
      >
        <ElFormItem label="每天仅能抽取一次" prop="dayOne">
          <ElSwitch
            v-model="tarotConfig.dayOne"
            :active-value="true"
            :inactive-value="false"
            active-text="启用"
            inactive-text="停用"
          />
        </ElFormItem>
        <ElFormItem label="启用保底" prop="fxxkDestiny">
          <ElSwitch
            v-model="tarotConfig.fxxkDestiny"
            :active-value="true"
            :inactive-value="false"
            active-text="启用"
            inactive-text="停用"
          />
        </ElFormItem>
        <ElFormItem label="卡面类型" prop="cardType">
          <ElSelect v-model="tarotConfig.cardType" default-first-option>
            <ElOption value="A" label="Shi0n老师" />
            <ElOption value="B" label="GoAnchor老师" />
          </ElSelect>
        </ElFormItem>
      </PluginPreferenceForm>
    </ElTabPane>
    <ElTabPane name="totalAssault" label="总力设置">
      <PluginPreferenceForm
        v-model:form="totalAssaultConfig"
        :default-form="defaultTotalAssaultConfig"
        p-id="com·diyigemt·arona"
        p-key="TotalAssaultConfig"
      >
        <ElFormItem label="总力档线默认服务器" prop="defaultTotalAssault">
          <ElSelect v-model="totalAssaultConfig.defaultTotalAssault" default-first-option>
            <ElOption value="JP" label="日服" />
            <ElOption value="CN" label="官服" />
            <ElOption value="B" label="B服" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="大决战档线默认服务器" prop="defaultTotalAssaultEx">
          <ElSelect v-model="totalAssaultConfig.defaultTotalAssaultEx" default-first-option>
            <ElOption value="JP" label="日服" />
            <ElOption value="CN" label="官服" />
            <ElOption value="B" label="B服" />
          </ElSelect>
        </ElFormItem>
      </PluginPreferenceForm>
    </ElTabPane>
    <ElTabPane name="gacha" label="抽卡">
      <PluginPreferenceForm
        v-model:form="contactGachaConfig"
        :default-form="defaultGachaConfig"
        p-id="com·diyigemt·arona"
        p-key="ContactGachaConfig"
      >
        <ElFormItem label="群每人日抽卡点数上限" prop="defaultTotalAssault">
          <ElInputNumber v-model="contactGachaConfig.limit" :step="10" :min="0"></ElInputNumber>
        </ElFormItem>
      </PluginPreferenceForm>
    </ElTabPane>
  </ElTabs>
</template>

<script setup lang="ts">
import PluginPreferenceForm from "@/components/plugin/PluginPreferenceForm.vue";
import { AronaApi } from "@/api/modules/arona";
import { SelectOptions } from "@/interface";
import { randomInt, useTableInlineEditor } from "@/utils";
import { warningMessage } from "@/utils/message";

defineOptions({
  name: "AronaPreferences",
});

interface TarotConfig {
  dayOne: boolean;
  fxxkDestiny: boolean;
  cardType: "A" | "B";
}

interface ContactGachaConfig {
  limit: number;
}

interface TrainerConfig {
  override: {
    id: string;
    type: "RAW";
    name: string[];
    value: string;
    edit: boolean;
  }[];
}

interface TotalAssaultConfig {
  defaultTotalAssault: "B" | "CN" | "JP";
  defaultTotalAssaultEx: "B" | "CN" | "JP";
}

const tab = ref<"trainer" | "tarot" | "totalAssault" | "gacha">("trainer");
const defaultTrainerConfig: TrainerConfig = {
  override: [],
};
const defaultGachaConfig: ContactGachaConfig = {
  limit: 0,
};
const defaultTarotConfig: TarotConfig = {
  dayOne: true,
  fxxkDestiny: false,
  cardType: "A",
};
const defaultTotalAssaultConfig: TotalAssaultConfig = {
  defaultTotalAssault: "JP",
  defaultTotalAssaultEx: "JP",
};
const trainerConfig = ref<TrainerConfig>(defaultTrainerConfig);
const tarotConfig = ref<TarotConfig>(defaultTarotConfig);
const totalAssaultConfig = ref<TotalAssaultConfig>(defaultTotalAssaultConfig);
const contactGachaConfig = ref<ContactGachaConfig>(defaultGachaConfig);
const trainerOverrideConfig = computed(() => trainerConfig.value.override);
const { onEdit, onCancel, onConfirm, cache: overrideItem } = useTableInlineEditor(trainerOverrideConfig);

const trainerSearchLoading = ref(false);
const aronaTrainerOptions = ref<SelectOptions>([]);

function onParseTrainerOption(data: TrainerConfig) {
  data.override.forEach((it) => {
    it.id = `${it.name.join("-")}-${it.value}`;
    it.edit = false;
  });
  return data;
}

function onSaveParseTrainerOption(data: TrainerConfig) {
  return {
    override: data.override
      .filter((it) => it.name.length > 0 && it.value)
      .map((it) => ({
        type: it.type,
        name: it.name,
        value: it.value,
      })),
  };
}

function onAddOverride() {
  trainerOverrideConfig.value.splice(0, 0, {
    id: `${randomInt(0, 1024)}`,
    type: "RAW",
    name: [],
    value: "",
    edit: false,
  });
}

function onDeleteOverride(override: TrainerConfig["override"][0]) {
  const index = trainerOverrideConfig.value.findIndex((it) => it.id === override.id);
  if (index !== -1) {
    trainerOverrideConfig.value.splice(index, 1);
  }
}

function onSearchTrainer(name: string) {
  if (name) {
    trainerSearchLoading.value = true;
    AronaApi.trainerImage(name).then((data) => {
      trainerSearchLoading.value = false;
      if (data && data.data) {
        aronaTrainerOptions.value = data.data.map((it) => ({ id: it.hash, value: it.name, label: it.name }));
      } else {
        warningMessage("别名搜索失败");
      }
    });
  }
}
</script>

<style lang="scss" scoped></style>
