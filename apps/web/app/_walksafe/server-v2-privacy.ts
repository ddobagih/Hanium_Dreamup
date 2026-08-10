/** Pure fail-closed consent transitions for server-v2 processing and persistent report storage. */
export type ServerV2PrivacyConsent = Readonly<{
  frameProcessingAllowed: boolean;
  reportStorageAllowed: boolean;
}>;

export function createServerV2PrivacyConsent(): ServerV2PrivacyConsent {
  return {
    frameProcessingAllowed: false,
    reportStorageAllowed: false
  };
}

export function allowServerV2FrameProcessing(
  consent: ServerV2PrivacyConsent
): ServerV2PrivacyConsent {
  return {
    ...consent,
    frameProcessingAllowed: true
  };
}

export function allowServerV2ReportStorage(
  consent: ServerV2PrivacyConsent
): ServerV2PrivacyConsent {
  if (!isServerV2FrameProcessingAllowed(consent)) {
    return consent;
  }
  return {
    ...consent,
    reportStorageAllowed: true
  };
}

export function withdrawServerV2ReportStorage(
  consent: ServerV2PrivacyConsent
): ServerV2PrivacyConsent {
  return {
    ...consent,
    reportStorageAllowed: false
  };
}

export function withdrawServerV2FrameProcessing(): ServerV2PrivacyConsent {
  return createServerV2PrivacyConsent();
}

export function isServerV2FrameProcessingAllowed(
  consent: ServerV2PrivacyConsent | null | undefined
): boolean {
  return consent?.frameProcessingAllowed === true;
}

export function isServerV2ReportStorageAllowed(
  consent: ServerV2PrivacyConsent | null | undefined
): boolean {
  return consent?.frameProcessingAllowed === true && consent.reportStorageAllowed === true;
}
