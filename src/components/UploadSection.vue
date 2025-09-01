<template>
  <div class="card bg-base-100 shadow-xl">
    <div class="card-body">
      <h2 class="card-title">上传段落</h2>
      <textarea 
        class="textarea textarea-bordered h-24" 
        placeholder="粘贴需要记忆的段落"
        v-model="paragraphText"
      ></textarea>
      <div class="card-actions justify-end">
        <button class="btn btn-primary" @click="uploadParagraph">上传</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { useMemoryStore } from '@/stores/memoryStore';

const memoryStore = useMemoryStore();
const paragraphText = ref('');

const uploadParagraph = () => {
  if (paragraphText.value.trim()) {
    memoryStore.addParagraph({
      id: Date.now(),
      text: paragraphText.value.trim(),
      createdAt: new Date().toISOString()
    });
    paragraphText.value = '';
  }
};
</script>