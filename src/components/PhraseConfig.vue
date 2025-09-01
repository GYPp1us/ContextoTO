<template>
  <div class="card bg-base-100 shadow-xl">
    <div class="card-body">
      <h2 class="card-title">配置词组</h2>
      <div v-if="paragraphs.length > 0">
        <select class="select select-bordered w-full mb-4" v-model="selectedParagraphId">
          <option disabled value="">选择段落</option>
          <option v-for="paragraph in paragraphs" :key="paragraph.id" :value="paragraph.id">
            {{ paragraph.text.substring(0, 30) }}...
          </option>
        </select>
        <div v-if="selectedParagraph">
          <div class="mb-4">
            <p class="mb-2">{{ selectedParagraph.text }}</p>
            <input 
              type="text" 
              class="input input-bordered w-full" 
              placeholder="输入词组"
              v-model="newPhrase.text"
            />
            <input 
              type="text" 
              class="input input-bordered w-full mt-2" 
              placeholder="输入释义"
              v-model="newPhrase.definition"
            />
            <button class="btn btn-primary mt-2" @click="addPhrase">添加词组</button>
          </div>
        </div>
      </div>
      <p v-else>请先上传段落</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import { useMemoryStore } from '@/stores/memoryStore';

const memoryStore = useMemoryStore();
const selectedParagraphId = ref('');
const newPhrase = ref({ text: '', definition: '' });

const paragraphs = computed(() => memoryStore.paragraphs);
const selectedParagraph = computed(() => 
  paragraphs.value.find(p => p.id === selectedParagraphId.value)
);

const addPhrase = () => {
  if (newPhrase.value.text.trim() && newPhrase.value.definition.trim()) {
    memoryStore.addPhrase({
      id: Date.now(),
      paragraphId: selectedParagraphId.value,
      text: newPhrase.value.text.trim(),
      definition: newPhrase.value.definition.trim(),
      easiness: 2.5,
      consecutiveCorrect: 0,
      nextReviewDate: new Date().toISOString()
    });
    newPhrase.value = { text: '', definition: '' };
  }
};
</script>