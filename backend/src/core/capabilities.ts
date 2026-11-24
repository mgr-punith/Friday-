export const LATEST_ACTION_PROTOCOL_VERSION = 5 as const;

export type ActionProtocolVersion = 1 | 2 | 3 | 4 | 5;

export type ActionName =
  | "call_contact"
  | "call_number"
  | "set_alarm"
  | "set_timer"
  | "open_app"
  | "close_app"
  | "set_ringer_mode"
  | "open_music"
  | "play_media"
  | "control_media"
  | "flashlight"
  | "set_volume"
  | "open_settings";

export type SequencePosition = "preparatory" | "terminal";

export interface CapabilityMetadata {
  readonly minimumVersion: ActionProtocolVersion;
  readonly sequencePosition: SequencePosition;
}

/** Version and sequencing rules shared by provider tool selection and response validation. */
export const CAPABILITIES = {
  call_contact: { minimumVersion: 1, sequencePosition: "terminal" },
  call_number: { minimumVersion: 1, sequencePosition: "terminal" },
  set_alarm: { minimumVersion: 1, sequencePosition: "terminal" },
  set_timer: { minimumVersion: 1, sequencePosition: "terminal" },
  open_app: { minimumVersion: 1, sequencePosition: "terminal" },
  close_app: { minimumVersion: 1, sequencePosition: "terminal" },
  set_ringer_mode: { minimumVersion: 1, sequencePosition: "preparatory" },
  open_music: { minimumVersion: 2, sequencePosition: "terminal" },
  play_media: { minimumVersion: 4, sequencePosition: "terminal" },
  control_media: { minimumVersion: 5, sequencePosition: "terminal" },
  flashlight: { minimumVersion: 2, sequencePosition: "preparatory" },
  set_volume: { minimumVersion: 2, sequencePosition: "preparatory" },
  open_settings: { minimumVersion: 3, sequencePosition: "terminal" }
} as const satisfies Readonly<Record<ActionName, CapabilityMetadata>>;

export function actionProtocolVersion(value: string | undefined, mediaControls?: string): ActionProtocolVersion {
  if (value === "5" || (value === "4" && mediaControls === "1")) return 5;
  return value === "4" ? 4 : value === "3" ? 3 : value === "2" ? 2 : 1;
}

export function supportsAction(action: ActionName, version: ActionProtocolVersion): boolean {
  return CAPABILITIES[action].minimumVersion <= version;
}

export function canPrecedeAnotherAction(action: ActionName): boolean {
  return CAPABILITIES[action].sequencePosition === "preparatory";
}
