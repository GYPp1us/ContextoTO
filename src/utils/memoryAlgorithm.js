export function calculateNextReview(phrase, performanceRating) {
  // SM-2 algorithm implementation
  const { easiness, consecutiveCorrect, nextReviewDate } = phrase;
  
  let newEasiness = easiness + (0.1 - (5 - performanceRating) * (0.08 + (5 - performanceRating) * 0.02));
  newEasiness = Math.max(1.3, newEasiness);
  
  let newConsecutiveCorrect = performanceRating >= 3 ? consecutiveCorrect + 1 : 0;
  
  let daysToNextReview;
  if (newConsecutiveCorrect === 1) {
    daysToNextReview = 1;
  } else if (newConsecutiveCorrect === 2) {
    daysToNextReview = 6;
  } else {
    daysToNextReview = Math.round((newConsecutiveCorrect - 1) * newEasiness);
  }
  
  const newNextReviewDate = new Date();
  newNextReviewDate.setDate(newNextReviewDate.getDate() + daysToNextReview);
  
  return {
    easiness: newEasiness,
    consecutiveCorrect: newConsecutiveCorrect,
    nextReviewDate: newNextReviewDate.toISOString()
  };
}