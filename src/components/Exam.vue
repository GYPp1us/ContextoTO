<template>
  <div class="card bg-base-100 shadow-xl">
    <div class="card-body">
      <h2 class="card-title">自查考试</h2>
      <div v-if="examQuestions.length > 0">
        <div v-for="(question, index) in examQuestions" :key="index" class="mb-4 p-4 border rounded-lg">
          <p class="font-bold">{{ question.text }}</p>
          <input 
            type="text" 
            class="input input-bordered w-full mt-2" 
            placeholder="输入答案"
            v-model="userAnswers[index]"
          />
          <button 
            class="btn btn-sm btn-primary mt-2" 
            @click="checkAnswer(index)"
          >
            检查
          </button>
          <p v-if="results[index] !== undefined" class="mt-2">
            {{ results[index] ? '✓ 正确' : '✗ 错误' }}
          </p>
        </div>
        <button class="btn btn-primary mt-4" @click="submitExam">提交考试</button>
      </div>
      <p v-else>没有可用的考试题目</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import { useMemoryStore } from '@/stores/memoryStore';

const memoryStore = useMemoryStore();
const userAnswers = ref([]);
const results = ref([]);

const examQuestions = computed(() => {
  return memoryStore.phrases
    .sort(() => Math.random() - 0.5)
    .slice(0, 5);
});

const checkAnswer = (index) => {
  if (userAnswers.value[index] === examQuestions.value[index].definition) {
    results.value[index] = true;
  } else {
    results.value[index] = false;
  }
};

const submitExam = () => {
  memoryStore.addExam({
    id: Date.now(),
    questions: examQuestions.value,
    answers: userAnswers.value,
    results: results.value,
    date: new Date().toISOString()
  });
  userAnswers.value = [];
  results.value = [];
};
</script>