import { execFileSync } from 'node:child_process';

const config = JSON.parse(execFileSync('docker', ['compose', 'config', '--format', 'json'], { encoding: 'utf8' }));
const services = ['users', 'auctions', 'orders', 'notifications'];
const localDev = services.every(name => {
  const env = config.services[name].environment;
  return env.SPRING_PROFILES_ACTIVE === 'dev' && ['localhost', '127.0.0.1', '[::1]'].includes(new URL(env.APP_ORIGIN).hostname);
});
if (!localDev) {
  console.error('Demo reset requires the dev profile and a loopback application origin.');
  process.exit(1);
}

execFileSync('docker', ['compose', 'down', '--volumes'], { stdio: 'inherit' });
execFileSync('docker', ['compose', 'up', '--build', '-d', '--wait', '--wait-timeout', '300'], { stdio: 'inherit' });
