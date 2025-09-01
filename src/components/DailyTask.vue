<template>
  <div class="card bg-base-100 shadow-xl">
    <div class="card-body">
      <h2 class="card-title">今日任务</h2>
      <div v-if="tasks.length > 0">
        <div v-for="task in tasks" :key="task.id" class="mb-4 p-4 border rounded-lg">
          <p class="font-bold">{{ task.text }}</p>
          <p class="text-sm text-gray-500">{{ task.definition }}</p>
          <div class="flex justify-between mt-2">
            <button 
              v-for="rating in [1, 2, 3, 4, 5]" 
              :key="rating"
              class="btn btn-sm"
              :class="{ 'btn-primary': rating === 3, 'btn-error': rating < 3, 'btn-success': rating > 3 }"
              @click="submitRating(task, rating)"
            >
              {{ rating }}
            </button>
          </div>
        </div>
      </div>
      <p v-else>今日没有任务</p>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useMemoryStore } from '@/stores/memoryStore';
import { calculateNextReview } from '@/utils/memoryAlgorithm';

const memoryStore = useMemoryStore();

const tasks = computed(() => {
  const today = new Date().toISOString().split('T')[0];
  return memoryStore.phrases.filter(
    phrase => phrase.nextReviewDate.split('T')[0] <= today
  );
});

const submitRating = (phrase, rating) => {
  const updatedPhrase = {
    ...phrase,
    ...calculateNextReview(phrase, rating)
  };
  // Update phrase in store (to be implemented)
};
</script>