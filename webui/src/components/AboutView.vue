<script setup lang="ts">
import {MiuixBasicComponent,MiuixCard} from 'miuix-vue';
import appIcon from '../assets/opairdrop.svg';
defineProps<{version?:string}>();
defineEmits<{licenses:[]}>();
</script>
<template>
 <section class="about-screen" aria-label="关于 OPAirDrop">
  <div class="about-identity">
   <img class="app-icon" :src="appIcon" width="120" height="120" alt="OPAirDrop 图标"/>
   <h1>OPAirDrop</h1>
   <p>版本: {{version||'读取中'}}</p>
   <p>作者: TimelessU</p>
  </div>
  <MiuixCard class="about-actions">
   <a class="project-link" href="https://github.com/Weverses/OPAirDrop" target="_blank" rel="noopener noreferrer"><MiuixBasicComponent title="在 GitHub 查看源码"><template #end><span class="entry-arrow" aria-hidden="true">›</span></template></MiuixBasicComponent></a>
   <MiuixBasicComponent title="开放源代码许可" clickable @click="$emit('licenses')"><template #end><span class="entry-arrow" aria-hidden="true">›</span></template></MiuixBasicComponent>
  </MiuixCard>
 </section>
</template>
