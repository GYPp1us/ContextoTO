import { createRouter, createWebHistory } from 'vue-router';
import UploadSection from '@/components/UploadSection.vue';
import PhraseConfig from '@/components/PhraseConfig.vue';
import DailyTask from '@/components/DailyTask.vue';
import Exam from '@/components/Exam.vue';

const routes = [
  { path: '/', redirect: '/upload' },
  { path: '/upload', component: UploadSection },
  { path: '/phrases', component: PhraseConfig },
  { path: '/tasks', component: DailyTask },
  { path: '/exam', component: Exam },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

export default router;