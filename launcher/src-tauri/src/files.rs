//! A profile's worlds (and backups), saved servers and screenshots.
use crate::{e, AppState, Res};
use kite_core::gamefiles::{self, Backup, Screenshot, World};
use kite_core::servers::{self, Ping, SavedServer};
use tauri::State;

async fn not_running(s: &AppState, profile_id: &str, what: &str) -> Res<()> {
    if s.running.lock().await.as_ref().is_some_and(|r| r.profile_id == profile_id) {
        return Err(format!("Close the game before you {what}."));
    }
    Ok(())
}

fn game_dir(s: &AppState, profile_id: &str) -> Res<std::path::PathBuf> {
    let p = s.profile(profile_id)?;
    Ok(s.game_dir(&p))
}

#[tauri::command]
pub async fn list_worlds(s: State<'_, AppState>, profile_id: String) -> Res<Vec<World>> {
    let dir = game_dir(&s, &profile_id)?;
    // Folder sizes walk every region file; keep that off the async workers.
    tauri::async_runtime::spawn_blocking(move || gamefiles::list_worlds(&dir)).await.map_err(e)
}

#[tauri::command]
pub async fn delete_world(s: State<'_, AppState>, profile_id: String, world: String) -> Res<()> {
    not_running(&s, &profile_id, "delete a world").await?;
    gamefiles::delete_world(&game_dir(&s, &profile_id)?, &world).map_err(e)?;
    s.log(format!("deleted world {world} in {profile_id}"));
    Ok(())
}

#[tauri::command]
pub async fn backup_world(s: State<'_, AppState>, profile_id: String, world: String) -> Res<Backup> {
    let dir = game_dir(&s, &profile_id)?;
    tauri::async_runtime::spawn_blocking(move || gamefiles::backup_world(&dir, &world)).await.map_err(e)?.map_err(e)
}

#[tauri::command]
pub fn list_backups(s: State<AppState>, profile_id: String) -> Res<Vec<Backup>> {
    Ok(gamefiles::list_backups(&game_dir(&s, &profile_id)?))
}

/// Unzip a backup into the saves folder. Returns the restored world's folder name.
#[tauri::command]
pub async fn restore_backup(s: State<'_, AppState>, profile_id: String, backup: String) -> Res<String> {
    let dir = game_dir(&s, &profile_id)?;
    tauri::async_runtime::spawn_blocking(move || gamefiles::restore_backup(&dir, &backup)).await.map_err(e)?.map_err(e)
}

#[tauri::command]
pub fn list_servers(s: State<AppState>, profile_id: String) -> Res<Vec<SavedServer>> {
    servers::list(&game_dir(&s, &profile_id)?).map_err(e)
}

#[tauri::command]
pub async fn add_server(s: State<'_, AppState>, profile_id: String, name: String, address: String) -> Res<()> {
    // The game rewrites servers.dat when it closes its server list, so edits while it runs could be lost.
    not_running(&s, &profile_id, "change the server list").await?;
    servers::add(&game_dir(&s, &profile_id)?, &name, &address).map_err(e)
}

#[tauri::command]
pub async fn remove_server(s: State<'_, AppState>, profile_id: String, index: usize, address: String) -> Res<()> {
    not_running(&s, &profile_id, "change the server list").await?;
    servers::remove(&game_dir(&s, &profile_id)?, index, &address).map_err(e)
}

#[tauri::command]
pub async fn ping_server(s: State<'_, AppState>, address: String) -> Res<Ping> {
    servers::ping(s.api.http(), &address).await.map_err(e)
}

#[tauri::command]
pub fn list_screenshots(s: State<AppState>, profile_id: String) -> Res<Vec<Screenshot>> {
    Ok(gamefiles::list_screenshots(&game_dir(&s, &profile_id)?))
}

#[tauri::command]
pub fn delete_screenshot(s: State<AppState>, profile_id: String, name: String) -> Res<()> {
    gamefiles::delete_screenshot(&game_dir(&s, &profile_id)?, &name).map_err(e)
}

/// Put a screenshot on the clipboard as an image.
#[tauri::command]
pub fn copy_screenshot(app: tauri::AppHandle, s: State<AppState>, profile_id: String, name: String) -> Res<()> {
    use tauri_plugin_clipboard_manager::ClipboardExt;
    let path = gamefiles::screenshot_path(&game_dir(&s, &profile_id)?, &name).map_err(e)?;
    if !path.extension().is_some_and(|x| x.eq_ignore_ascii_case("png")) {
        return Err("Only PNG screenshots can be copied".into());
    }
    let bytes = std::fs::read(&path).map_err(e)?;
    let image = tauri::image::Image::from_bytes(&bytes).map_err(e)?;
    app.clipboard().write_image(&image).map_err(e)
}
