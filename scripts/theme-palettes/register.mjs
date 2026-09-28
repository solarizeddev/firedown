// material-color-utilities 0.4.0 ships ESM with EXTENSIONLESS relative
// imports ('./hct/hct'), which Node's resolver rejects. Retry with '.js'.
import { register } from 'node:module';

register('./resolve-js.mjs', import.meta.url);
