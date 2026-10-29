/** One bounded page per retry; never visits private channels or changes reply bodies. */
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { HttpsError } from 'firebase-functions/v2/https';
export async function redactPublicReplies(messageId: string) {
  const query = db
    .collection('communityChat')
    .doc('global')
    .collection('messages')
    .where('replyTo.messageId', '==', messageId);
  const page = await query.limit(201).get();
  let redacted = 0;
  for (let offset = 0; offset < Math.min(page.size, 200); offset += 50) {
    const refs = page.docs.slice(offset, Math.min(offset + 50, 200)).map((d) => d.ref);
    redacted += await db.runTransaction(async (tx) => {
      const parent = await tx.get(
        db.collection('communityChat').doc('global').collection('messages').doc(messageId),
      );
      if (parent.exists)
        throw new HttpsError(
          'failed-precondition',
          'Det publika meddelandet finns igen. Granska den aktuella versionen.',
        );
      const current = await tx.getAll(...refs);
      let count = 0;
      for (const doc of current)
        if (doc.get('replyTo')?.messageId === messageId) {
          tx.update(doc.ref, { replyTo: FieldValue.delete() });
          count++;
        }
      return count;
    });
  }
  return { redacted, hasMore: page.size > 200 };
}
