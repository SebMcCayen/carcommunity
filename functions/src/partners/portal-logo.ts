import sharp from 'sharp';
import { HttpsError } from 'firebase-functions/v2/https';

/** No external URLs, SVG or client MIME trust; all public logos are fresh PNG pixels. */
export async function sanitizeLogo(base64: string): Promise<Buffer> {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(base64) || base64.length > 7_000_000)
    throw new HttpsError('invalid-argument', 'Upload a PNG logo up to 5 MB.');
  const input = Buffer.from(base64, 'base64');
  if (
    input.length > 5 * 1024 * 1024 ||
    !input.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
  )
    throw new HttpsError('invalid-argument', 'Upload a PNG logo up to 5 MB.');
  try {
    const image = sharp(input, {
      limitInputPixels: 16_000_000,
      failOn: 'warning',
      animated: false,
    });
    const metadata = await image.metadata();
    if (
      metadata.format !== 'png' ||
      !metadata.width ||
      !metadata.height ||
      (metadata.pages ?? 1) !== 1
    )
      throw new Error('Unsupported image');
    // Re-encode without keepMetadata/withMetadata; retain alpha and proportions.
    for (const size of [512, 384, 256]) {
      const output = await image
        .clone()
        .resize({ width: size, height: size, fit: 'inside', withoutEnlargement: true })
        .png()
        .toBuffer();
      if (output.length <= 450_000) return output;
    }
    throw new Error('Encoded preview exceeds the inline budget');
  } catch {
    throw new HttpsError(
      'invalid-argument',
      'The PNG could not be decoded safely (maximum 16 megapixels).',
    );
  }
}
