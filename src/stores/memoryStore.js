import { defineStore } from 'pinia';

export const useMemoryStore = defineStore('memory', {
  state: () => ({
    paragraphs: [],
    phrases: [],
    dailyTasks: [],
    exams: []
  }),
  actions: {
    addParagraph(paragraph) {
      this.paragraphs.push(paragraph);
    },
    addPhrase(phrase) {
      this.phrases.push(phrase);
    },
    updatePhrase(updatedPhrase) {
      const index = this.phrases.findIndex(p => p.id === updatedPhrase.id);
      if (index !== -1) {
        this.phrases[index] = updatedPhrase;
      }
    },
    scheduleDailyTasks() {
      // Implement memory curve algorithm here
    },
    addExam(exam) {
      this.exams.push(exam);
    }
  }
});