import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({base:'./',plugins:[vue()],build:{target:'es2020',sourcemap:false}});
