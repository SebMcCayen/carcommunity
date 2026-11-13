/** Native-consumable source: contracts/schemas/road-cameras.schema.json. */
export type RoadCameraType = 'speed' | 'traffic' | 'road_condition';
export interface RoadCamera {
  id: string;
  type: RoadCameraType;
  name: string;
  latitude: number;
  longitude: number;
  direction: number | null;
}
export interface RoadCameraCatalog {
  cameras: RoadCamera[];
  updatedAt: string | null;
}
export interface RoadCameraPhotoRequest {
  cameraId: string;
}
export interface RoadCameraPhoto {
  base64: string;
  contentType: 'image/jpeg' | 'image/png';
  /** HTTP image update time; absent source timestamps remain null. */
  photoTime: string | null;
  cacheSeconds: 300;
}
