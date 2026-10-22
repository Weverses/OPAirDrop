<script setup lang="ts">
import {MiuixCard,MiuixSmallTitle} from 'miuix-vue';
import {licenseGroups} from '../licenses';
</script>
<template>
 <section class="licenses-screen" aria-label="开放源代码许可">
  <template v-for="group in licenseGroups" :key="group.title">
   <MiuixSmallTitle :text="group.title"/>
   <MiuixCard>
    <details v-for="project in group.projects" :key="project.url" class="license-project">
     <summary><span class="license-overview"><span class="license-name">{{project.name}}</span><span class="license-usage">{{project.usage}}</span><span class="license-badge">{{project.license}}</span></span><span class="license-chevron" aria-hidden="true">›</span></summary>
     <div class="license-details">
      <p v-if="project.note">{{project.note}}</p>
      <div class="license-links"><a :href="project.url" target="_blank" rel="noopener noreferrer">项目源码 ↗</a><a v-if="project.licenseUrl" :href="project.licenseUrl" target="_blank" rel="noopener noreferrer">许可原文 ↗</a></div>
     </div>
    </details>
   </MiuixCard>
  </template>
 </section>
</template>
