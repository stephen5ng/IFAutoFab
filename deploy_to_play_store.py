
import os
import sys
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload
from google.oauth2 import service_account

# Configuration
PACKAGE_NAME = 'com.ifautofab'
BUNDLE_PATH = 'app/build/outputs/bundle/release/app-release.aab'
KEY_FILE = 'play-store-key.json'
TRACK = 'internal'

def deploy():
    if not os.path.exists(KEY_FILE):
        print(f"Error: {KEY_FILE} not found in project root.")
        return

    if not os.path.exists(BUNDLE_PATH):
        print(f"Error: {BUNDLE_PATH} not found. Did you run './gradlew :app:bundleRelease'?")
        return

    print(f"Starting deployment for {PACKAGE_NAME}...")

    # Authenticate
    credentials = service_account.Credentials.from_service_account_file(KEY_FILE)
    service = build('androidpublisher', 'v3', credentials=credentials)

    # Create a new edit
    edit_request = service.edits().insert(packageName=PACKAGE_NAME, body={})
    edit_results = edit_request.execute()
    edit_id = edit_results['id']

    print(f"Created edit with ID: {edit_id}")

    # Upload the bundle
    print(f"Uploading {BUNDLE_PATH}...")
    bundle_file = MediaFileUpload(BUNDLE_PATH, mimetype='application/octet-stream', resumable=True)
    upload_request = service.edits().bundles().upload(
        packageName=PACKAGE_NAME,
        editId=edit_id,
        media_body=bundle_file
    )
    
    bundle_response = upload_request.execute()
    version_code = bundle_response['versionCode']
    print(f"Successfully uploaded bundle version {version_code}")

    # Assign bundle to track
    def update_track(status):
        print(f"Assigning version {version_code} to {TRACK} track as {status}...")
        track_request = service.edits().tracks().update(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            track=TRACK,
            body={
                'releases': [{
                    'versionCodes': [str(version_code)],
                    'status': status
                }]
            }
        )
        track_request.execute()

    try:
        update_track('completed')
        print("Committing changes to Google Play...")
        service.edits().commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
    except Exception as e:
        if "Only releases with status draft may be created on draft app" in str(e):
            print("App is still in 'Draft' state. Falling back to draft release...")
            update_track('draft')
            service.edits().commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
        else:
            raise e

    print("Deployment successful! Check the Google Play Console to review and roll out.")

if __name__ == '__main__':
    try:
        deploy()
    except Exception as e:
        print(f"Error during deployment: {e}")
        sys.exit(1)
