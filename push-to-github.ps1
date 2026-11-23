$ErrorActionPreference = "Stop"
Write-Host "ULTRON AI GitHub push helper" -ForegroundColor Cyan
if (-not (Test-Path ".git")) { git init }
git add .
git status
git commit -m "Initial ULTRON AI project"
git branch -M main
$remote = git remote get-url origin 2>$null
if (-not $remote) {
  $url = Read-Host "Paste your GitHub repository URL (https://github.com/USERNAME/ULTRON-AI.git)"
  git remote add origin $url
}
git push -u origin main
